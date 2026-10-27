package com.reservation.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two connections race conflicting calendar writes. The first one runs its write and
 * holds its transaction open; the second runs on another thread and must wait for the
 * calendar lock. The test confirms the second is really blocked (pg_stat_activity),
 * commits the first, and then checks that exactly one of the two committed.
 */
@Testcontainers(disabledWithoutDocker = true)
class CalendarConcurrencyDbTest extends CalendarDbTestBase {

    interface SqlAction {
        void run(Connection connection) throws Exception;
    }

    record Outcome(boolean committed, String sqlState) {
    }

    record Race(Outcome first, Outcome second, boolean secondWaited) {
        int commits() {
            return (first.committed() ? 1 : 0) + (second.committed() ? 1 : 0);
        }
    }

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private Race race(String firstIsolation, SqlAction first, String secondIsolation, SqlAction second)
        throws Exception {
        Connection a = serviceRole();
        Connection b = serviceRole();
        Connection observer = superuser();
        int bPid = (int) scalarLong(b, "SELECT pg_backend_pid()");
        exec(b, "SET lock_timeout = '30s'");
        a.setAutoCommit(false);
        b.setAutoCommit(false);
        exec(a, "SET TRANSACTION ISOLATION LEVEL " + firstIsolation);

        Outcome firstOutcome = null;
        try {
            first.run(a);
        } catch (PSQLException error) {
            a.rollback();
            fail("first writer failed on its own: " + error.getMessage());
        }

        Future<Outcome> secondResult = pool.submit(() -> attempt(b, secondIsolation, second));
        boolean waited = waitUntilBlocked(observer, bPid, secondResult);

        try {
            a.commit();
            firstOutcome = new Outcome(true, null);
        } catch (PSQLException error) {
            a.rollback();
            firstOutcome = new Outcome(false, error.getSQLState());
        }

        Outcome secondOutcome = secondResult.get(60, TimeUnit.SECONDS);
        a.setAutoCommit(true);
        b.setAutoCommit(true);
        return new Race(firstOutcome, secondOutcome, waited);
    }

    private static Outcome attempt(Connection connection, String isolation, SqlAction action) throws SQLException {
        try {
            exec(connection, "SET TRANSACTION ISOLATION LEVEL " + isolation);
            action.run(connection);
            connection.commit();
            return new Outcome(true, null);
        } catch (PSQLException error) {
            connection.rollback();
            return new Outcome(false, error.getSQLState());
        } catch (Exception error) {
            connection.rollback();
            return new Outcome(false, "JAVA:" + error);
        }
    }

    private static boolean waitUntilBlocked(Connection observer, int pid, Future<?> second) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !second.isDone()) {
            Object waiting = scalar(observer,
                "SELECT coalesce(bool_or(wait_event_type = 'Lock'), false) FROM pg_stat_activity WHERE pid = ?", pid);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static final String RC = "READ COMMITTED";
    private static final String SER = "SERIALIZABLE";

    private SqlAction reserve(LocalDate day) {
        return c -> eventAt(c, at(day, 10, 0), at(day, 11, 0));
    }

    private SqlAction close(LocalDate day) {
        return c -> closure(c, day);
    }

    private long eventsOnClosedDays() throws SQLException {
        return count(
            "SELECT count(*) FROM public.events e " +
                "CROSS JOIN LATERAL public.calendar_event_dates(e.start_time, e.end_time) d(local_date) " +
                "JOIN public.unavailable_days u ON u.unavailable_date = d.local_date " +
                // A reservation may sit on a closed day only if an emergency closure kept it,
                // which leaves a close_day audit row for that reservation and date.
                "WHERE NOT EXISTS (SELECT 1 FROM public.calendar_override_audit a " +
                "  WHERE a.action = 'close_day' AND a.event_id = e.id AND u.unavailable_date = ANY (a.restricted_dates))");
    }

    private long eventsOutsideSemesters() throws SQLException {
        return count(
            "SELECT count(*) FROM public.events e " +
                "CROSS JOIN LATERAL public.calendar_event_dates(e.start_time, e.end_time) d(local_date) " +
                "WHERE NOT EXISTS (SELECT 1 FROM public.semesters s WHERE d.local_date BETWEEN s.start_date AND s.end_date)");
    }

    // ---- Reservation vs closure ----------------------------------------------------

    @Test
    void reservationFirstThenClosure() throws Exception {
        currentSemester();
        LocalDate day = today.plusDays(10);

        Race race = race(RC, reserve(day), RC, close(day));

        assertTrue(race.secondWaited(), "closure did not wait for the calendar lock");
        assertEquals(1, race.commits(), race.toString());
        assertTrue(race.first().committed());
        assertEquals("BTC02", race.second().sqlState());
        assertEquals(1, count("SELECT count(*) FROM public.events"));
        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
    }

    @Test
    void closureFirstThenReservation() throws Exception {
        currentSemester();
        LocalDate day = today.plusDays(10);

        Race race = race(RC, close(day), RC, reserve(day));

        assertTrue(race.secondWaited(), "reservation did not wait for the calendar lock");
        assertEquals(1, race.commits(), race.toString());
        assertEquals("BTC01", race.second().sqlState());
        assertEquals(0, count("SELECT count(*) FROM public.events"));
        assertEquals(1, count("SELECT count(*) FROM public.unavailable_days"));
    }

    @Test
    void secondWriterSucceedsWhenTheFirstRollsBack() throws Exception {
        currentSemester();
        LocalDate day = today.plusDays(10);
        Connection a = serviceRole();
        Connection b = serviceRole();
        int bPid = (int) scalarLong(b, "SELECT pg_backend_pid()");
        a.setAutoCommit(false);
        b.setAutoCommit(false);

        eventAt(a, at(day, 10, 0), at(day, 11, 0));
        Future<Outcome> closing = pool.submit(() -> attempt(b, RC, close(day)));
        assertTrue(waitUntilBlocked(superuser(), bPid, closing));
        a.rollback();

        assertTrue(closing.get(60, TimeUnit.SECONDS).committed());
        assertEquals(1, count("SELECT count(*) FROM public.unavailable_days"));
    }

    @Test
    void emergencyClosureRacingAReservationAuditsItAsCloseDay() throws Exception {
        currentSemester();
        LocalDate day = today.plusDays(10);

        Race race = race(RC, reserve(day), RC, c -> scalar(c,
            "SELECT count(*) FROM public.create_unavailable_day_with_override(1, 'Gas leak', ?::jsonb)",
            "{\"unavailable_date\":\"" + day + "\"}"));

        assertTrue(race.secondWaited());
        assertEquals(2, race.commits(), "an override closure keeps the reservation: " + race);
        int eventId = (int) count("SELECT id FROM public.events");
        // The closure saw the reservation committed while it waited, and audited it.
        assertEquals(1, count("SELECT count(*) FROM public.calendar_override_audit"));
        assertEquals(1, count(
            "SELECT count(*) FROM public.calendar_override_audit WHERE action = 'close_day' AND event_id = ? " +
                "AND actor_user_id = 1 AND reason = 'Gas leak' AND overridden_rules = ARRAY['existing_reservations'] " +
                "AND restricted_dates = ARRAY[?::date]", eventId, day));
        assertEquals(0, eventsOnClosedDays());
    }

    @Test
    void emergencyClosureFirstThenReservation() throws Exception {
        currentSemester();
        LocalDate day = today.plusDays(10);

        Race race = race(RC, c -> scalar(c,
            "SELECT count(*) FROM public.create_unavailable_day_with_override(1, 'Gas leak', ?::jsonb)",
            "{\"unavailable_date\":\"" + day + "\"}"), RC, reserve(day));

        assertTrue(race.secondWaited(), "reservation did not wait for the calendar lock");
        assertEquals(1, race.commits(), race.toString());
        assertEquals("BTC01", race.second().sqlState());
        assertEquals(0, count("SELECT count(*) FROM public.events"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
        assertEquals(0, eventsOnClosedDays());
    }

    @Test
    void reservationMovedOntoADayWhileItIsBeingClosed() throws Exception {
        currentSemester();
        LocalDate day = today.plusDays(10);
        int id = eventAt(db, at(day.plusDays(1), 10, 0), at(day.plusDays(1), 11, 0));

        Race race = race(RC, close(day), RC, c -> exec(c,
            "UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?",
            at(day, 10, 0), at(day, 11, 0), id));

        assertTrue(race.secondWaited(), "move did not wait for the calendar lock");
        assertEquals(1, race.commits(), race.toString());
        assertEquals("BTC01", race.second().sqlState());
        assertEquals(0, eventsOnClosedDays());
    }

    // ---- Semester shrink vs reservation ----------------------------------------------

    @Test
    void reservationFirstThenSemesterShrink() throws Exception {
        LocalDate end = today.plusDays(60);
        long id = semester("Fall", today.plusDays(1), end);

        Race race = race(RC, reserve(end), RC,
            c -> exec(c, "UPDATE public.semesters SET end_date = ?::date WHERE id = ?", end.minusDays(1), id));

        assertTrue(race.secondWaited(), "semester update did not wait for the calendar lock");
        assertEquals(1, race.commits(), race.toString());
        assertEquals("BTC03", race.second().sqlState());
        assertEquals(0, eventsOutsideSemesters());
    }

    @Test
    void semesterShrinkFirstThenReservation() throws Exception {
        LocalDate end = today.plusDays(60);
        long id = semester("Fall", today.plusDays(1), end);

        Race race = race(RC,
            c -> exec(c, "UPDATE public.semesters SET end_date = ?::date WHERE id = ?", end.minusDays(1), id),
            RC, reserve(end));

        assertTrue(race.secondWaited(), "reservation did not wait for the calendar lock");
        assertEquals(1, race.commits(), race.toString());
        assertEquals("BTC01", race.second().sqlState());
        assertEquals(0, eventsOutsideSemesters());
    }

    @Test
    void semesterDeleteFirstThenReservation() throws Exception {
        LocalDate end = today.plusDays(60);
        long id = semester("Fall", today.plusDays(1), end);

        Race race = race(RC, c -> exec(c, "DELETE FROM public.semesters WHERE id = ?", id), RC, reserve(end));

        assertEquals(1, race.commits(), race.toString());
        assertEquals("BTC01", race.second().sqlState());
        assertEquals(0, eventsOutsideSemesters());
    }

    // ---- SERIALIZABLE writers ----------------------------------------------------------

    /**
     * Calendar writes are only allowed under READ COMMITTED. Two SERIALIZABLE writers for
     * the same day, with both transactions open at once, are each refused with 0A000, so
     * neither can commit and nothing is written, in either order.
     */
    @Test
    void twoSerializableWritersAreEachRefusedSoNeitherCommitsInEitherOrder() throws Exception {
        currentSemester();
        LocalDate dayA = today.plusDays(10);
        LocalDate dayB = today.plusDays(11);

        List<Outcome> reservationFirst = serializablePair(reserve(dayA), close(dayA));
        List<Outcome> closureFirst = serializablePair(close(dayB), reserve(dayB));

        for (List<Outcome> pair : List.of(reservationFirst, closureFirst)) {
            assertEquals(List.of(new Outcome(false, "0A000"), new Outcome(false, "0A000")), pair);
        }
        assertEquals(0, count("SELECT count(*) FROM public.events"));
        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals(0, eventsOnClosedDays());
    }

    /** Runs both writes in two concurrently open SERIALIZABLE transactions, then tries to commit each. */
    private List<Outcome> serializablePair(SqlAction first, SqlAction second) throws Exception {
        Connection a = serviceRole();
        Connection b = serviceRole();
        exec(a, "SET lock_timeout = '10s'");
        exec(b, "SET lock_timeout = '10s'");
        a.setAutoCommit(false);
        b.setAutoCommit(false);
        exec(a, "SET TRANSACTION ISOLATION LEVEL " + SER);
        exec(b, "SET TRANSACTION ISOLATION LEVEL " + SER);

        String firstState = refusalState(a, first);
        // a's transaction is still open while b writes.
        String secondState = refusalState(b, second);

        Outcome firstOutcome = commitOrReport(a, firstState);
        Outcome secondOutcome = commitOrReport(b, secondState);
        a.setAutoCommit(true);
        b.setAutoCommit(true);
        return List.of(firstOutcome, secondOutcome);
    }

    private static String refusalState(Connection connection, SqlAction action) {
        try {
            action.run(connection);
            return null;
        } catch (PSQLException error) {
            return error.getSQLState();
        } catch (Exception error) {
            return "JAVA:" + error;
        }
    }

    // The write's refusal is the outcome; a commit after a refusal can only roll back.
    private static Outcome commitOrReport(Connection connection, String refusal) throws SQLException {
        try {
            connection.commit();
        } catch (PSQLException error) {
            connection.rollback();
            return new Outcome(false, refusal == null ? error.getSQLState() : refusal);
        }
        return new Outcome(refusal == null, refusal);
    }

    /**
     * A SERIALIZABLE writer's snapshot predates the lock wait, and Postgres only catches the
     * race when both sides are SERIALIZABLE, so against the default READ COMMITTED writer it
     * would check stale data. The migration therefore refuses every level but READ COMMITTED;
     * this race must end with exactly one commit.
     */
    @Test
    void serializableWriterRacingAReadCommittedWriterNeverBothCommit() throws Exception {
        currentSemester();
        LocalDate dayA = today.plusDays(10);
        LocalDate dayB = today.plusDays(11);

        Race reservationFirst = race(RC, reserve(dayA), SER, close(dayA));
        Race closureFirst = race(RC, close(dayB), SER, reserve(dayB));

        assertAll(
            () -> assertEquals(1, reservationFirst.commits(), "RC reservation vs SERIALIZABLE closure: " + reservationFirst),
            () -> assertEquals(1, closureFirst.commits(), "RC closure vs SERIALIZABLE reservation: " + closureFirst),
            () -> assertEquals(0, eventsOnClosedDays(), "reservations on closed days without an override")
        );
    }
}
