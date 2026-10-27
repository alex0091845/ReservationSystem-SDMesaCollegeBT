package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reservation (events) rules enforced by the migration's triggers. */
@Testcontainers(disabledWithoutDocker = true)
class CalendarReservationRulesDbTest extends CalendarDbTestBase {

    @Test
    void withNoSemesterConfiguredEveryReservationIsRefused() throws Exception {
        LocalDate day = today.plusDays(5);

        PSQLException error = fails("BTC01", () -> event(day, 10, 11));

        JsonNode detail = detail(error);
        assertEquals(List.of(day.toString()), texts(detail.path("dates")));
        assertEquals(List.of(day.toString()), texts(detail.path("outside_semester_dates")));
        assertEquals(List.of(), texts(detail.path("unavailable_dates")));
        assertEquals("Reservation date is outside the configured semester.", error.getServerErrorMessage().getMessage());
        assertEquals(0, count("SELECT count(*) FROM public.events"));
    }

    @Test
    void semesterStartAndEndAreInclusiveAndTheDaysAroundThemAreRefused() throws Exception {
        LocalDate start = today.plusDays(10);
        LocalDate end = today.plusDays(40);
        semester("Fall", start, end);

        event(start, 8, 9);
        event(end, 16, 17);

        PSQLException before = fails("BTC01", () -> event(start.minusDays(1), 10, 11));
        PSQLException after = fails("BTC01", () -> event(end.plusDays(1), 10, 11));

        assertEquals(List.of(start.minusDays(1).toString()), texts(detail(before).path("outside_semester_dates")));
        assertEquals(List.of(end.plusDays(1).toString()), texts(detail(after).path("outside_semester_dates")));
        assertEquals(2, count("SELECT count(*) FROM public.events"));
    }

    @Test
    void reservationEndingAtMidnightAfterTheLastSemesterDayIsAllowed() throws Exception {
        LocalDate end = today.plusDays(40);
        semester("Fall", today.plusDays(1), end);

        eventAt(at(end, 23, 0), at(end.plusDays(1), 0, 0));

        assertEquals(1, count("SELECT count(*) FROM public.events"));
        fails("BTC01", () -> eventAt(at(end.plusDays(1), 0, 0), at(end.plusDays(1), 0, 30)));
    }

    @Test
    void closedDayIsRefusedButNeighbouringDaysAreNot() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(20);
        closure(closed);

        PSQLException error = fails("BTC01", () -> event(closed, 10, 11));

        JsonNode detail = detail(error);
        assertEquals("Reservation date is unavailable.", error.getServerErrorMessage().getMessage());
        assertEquals(List.of(closed.toString()), texts(detail.path("unavailable_dates")));
        assertEquals(List.of(closed.toString()), texts(detail.path("dates")));
        assertEquals(List.of(), texts(detail.path("outside_semester_dates")));

        event(closed.minusDays(1), 10, 11);
        event(closed.plusDays(1), 10, 11);
        eventAt(at(closed.minusDays(1), 23, 0), at(closed, 0, 0));
        assertEquals(3, count("SELECT count(*) FROM public.events"));
        fails("BTC01", () -> eventAt(at(closed, 0, 0), at(closed, 0, 1)));
        fails("BTC01", () -> eventAt(at(closed.minusDays(1), 23, 0), at(closed, 0, 1)));
    }

    @Test
    void multiDayReservationReportsEveryRestrictedDate() throws Exception {
        LocalDate end = today.plusDays(40);
        semester("Fall", today.plusDays(1), end);
        LocalDate closed = end.minusDays(1);
        closure(closed);

        PSQLException error = fails("BTC01",
            () -> eventAt(at(closed.minusDays(1), 10, 0), at(end.plusDays(2), 10, 0)));

        JsonNode detail = detail(error);
        assertEquals(List.of(closed.toString()), texts(detail.path("unavailable_dates")));
        assertEquals(List.of(end.plusDays(1).toString(), end.plusDays(2).toString()),
            texts(detail.path("outside_semester_dates")));
        assertEquals(List.of(closed.toString(), end.plusDays(1).toString(), end.plusDays(2).toString()),
            texts(detail.path("dates")));
    }

    @Test
    void batchInsertIsAllOrNothingAndNamesEveryRestrictedDate() throws Exception {
        LocalDate end = today.plusDays(100);
        semester("Fall", today.plusDays(1), end);
        LocalDate closedA = today.plusDays(20);
        LocalDate closedB = today.plusDays(30);
        closure(closedA);
        closure(closedB);
        LocalDate outside = end.plusDays(5);

        String rows = eventsJson(new Instant[][] {
            {at(today.plusDays(10), 9, 0), at(today.plusDays(10), 10, 0)},
            {at(closedB, 9, 0), at(closedB, 10, 0)},
            {at(outside, 9, 0), at(outside, 10, 0)},
            {at(closedA, 9, 0), at(closedA, 10, 0)},
            {at(closedA, 11, 0), at(closedA, 12, 0)},
        });

        PSQLException error = fails("BTC01", () -> bulkInsert(rows));

        JsonNode detail = detail(error);
        assertEquals(List.of(closedA.toString(), closedB.toString(), outside.toString()), texts(detail.path("dates")));
        assertEquals(List.of(closedA.toString(), closedB.toString()), texts(detail.path("unavailable_dates")));
        assertEquals(List.of(outside.toString()), texts(detail.path("outside_semester_dates")));
        assertEquals(0, count("SELECT count(*) FROM public.events"), "no row of the batch may be kept");
    }

    @Test
    void weeklyRecurringSeriesInOneStatementIsAllOrNothing() throws Exception {
        LocalDate first = today.plusDays(7);
        LocalDate end = first.plusWeeks(4);
        semester("Fall", today.plusDays(1), end);
        closure(first.plusWeeks(2));

        Instant[][] weeks = new Instant[7][];
        for (int week = 0; week < weeks.length; week++) {
            LocalDate day = first.plusWeeks(week);
            weeks[week] = new Instant[] {at(day, 13, 0), at(day, 14, 30)};
        }

        PSQLException error = fails("BTC01", () -> bulkInsert(eventsJson(weeks)));

        JsonNode detail = detail(error);
        assertEquals(List.of(first.plusWeeks(2).toString()), texts(detail.path("unavailable_dates")));
        assertEquals(List.of(first.plusWeeks(5).toString(), first.plusWeeks(6).toString()),
            texts(detail.path("outside_semester_dates")));
        assertEquals(0, count("SELECT count(*) FROM public.events"));

        // The same series without the bad weeks goes through in full.
        bulkInsert(eventsJson(new Instant[][] {weeks[0], weeks[1], weeks[3], weeks[4]}));
        assertEquals(4, count("SELECT count(*) FROM public.events WHERE recurrence_group_id = 'g1'"));
    }

    // ---- Los Angeles dates across DST ------------------------------------------

    @Test
    void fallBackDayHas25HoursForReservationsAndClosures() throws Exception {
        LocalDate fallBack = nextDstChange(true);
        semester("DST", fallBack.minusDays(20), fallBack.plusDays(20));
        closure(fallBack);

        // 23:30 local on the 25-hour day is still that day (UTC offset is -8 by then).
        fails("BTC01", () -> eventAt(at(fallBack, 23, 30), at(fallBack.plusDays(1), 0, 0)));
        // The repeated 1 AM hour.
        fails("BTC01", () -> eventAt(at(fallBack, 0, 30), at(fallBack, 1, 30)));
        // Ends exactly at local midnight before the closed day: allowed.
        eventAt(at(fallBack.minusDays(1), 23, 0), at(fallBack, 0, 0));
        // Starts exactly at local midnight after the closed day: allowed.
        eventAt(at(fallBack.plusDays(1), 0, 0), at(fallBack.plusDays(1), 1, 0));

        assertEquals(fallBack.toString(),
            text("SELECT public.calendar_local_date(?::timestamptz)::text", at(fallBack, 23, 59)));
        assertEquals(fallBack.minusDays(1).toString(),
            text("SELECT string_agg(d::text, ',') FROM public.calendar_event_dates(?::timestamptz, ?::timestamptz) d",
                at(fallBack.minusDays(1), 23, 0), at(fallBack, 0, 0)));
    }

    @Test
    void closureOnFallBackDayDetectsReservationInTheExtraHour() throws Exception {
        LocalDate fallBack = nextDstChange(true);
        semester("DST", fallBack.minusDays(20), fallBack.plusDays(20));
        int late = eventAt(at(fallBack, 23, 15), at(fallBack, 23, 45));
        eventAt(at(fallBack.plusDays(1), 0, 0), at(fallBack.plusDays(1), 0, 30));

        PSQLException error = fails("BTC02", () -> closure(fallBack));

        JsonNode reservations = detail(error).path("reservations");
        assertEquals(1, reservations.size(), reservations.toString());
        assertEquals(late, reservations.get(0).path("id").asInt());
        // The next day only has the 00:00 reservation, and the late one must not leak into it.
        PSQLException next = fails("BTC02", () -> closure(fallBack.plusDays(1)));
        assertEquals(1, detail(next).path("reservations").size());
        closure(fallBack.minusDays(1));
    }

    @Test
    void springForwardDayHas23HoursForReservationsAndClosures() throws Exception {
        LocalDate springForward = nextDstChange(false);
        semester("DST", springForward.minusDays(20), springForward.plusDays(20));
        int late = eventAt(at(springForward, 23, 30), at(springForward.plusDays(1), 0, 0));

        PSQLException error = fails("BTC02", () -> closure(springForward));
        assertEquals(late, detail(error).path("reservations").get(0).path("id").asInt());

        closure(springForward.plusDays(1));
        fails("BTC01", () -> eventAt(at(springForward.plusDays(1), 0, 0), at(springForward.plusDays(1), 0, 30)));
        eventAt(at(springForward, 22, 0), at(springForward.plusDays(1), 0, 0));
        assertEquals(springForward.toString(),
            text("SELECT public.calendar_local_date(?::timestamptz)::text", at(springForward, 23, 59)));
    }

    // ---- Updates -------------------------------------------------------------

    @Test
    void updatesAreRecheckedOnlyWhenLocalDatesChange() throws Exception {
        currentSemester();
        LocalDate free = today.plusDays(10);
        LocalDate closed = today.plusDays(11);
        int id = event(free, 10, 11);
        closure(closed);

        exec("UPDATE public.events SET title = 'Renamed' WHERE id = ?", id);
        exec("UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?",
            at(free, 13, 0), at(free, 14, 0), id);

        PSQLException moved = fails("BTC01", () -> exec(
            "UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?",
            at(closed, 13, 0), at(closed, 14, 0), id));
        assertEquals(List.of(closed.toString()), texts(detail(moved).path("unavailable_dates")));

        fails("BTC01", () -> exec(
            "UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?",
            at(today.plusDays(400), 13, 0), at(today.plusDays(400), 14, 0), id));
        assertEquals(at(free, 13, 0).toString(),
            text("SELECT to_char(start_time AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM public.events WHERE id = ?", id));
    }

    @Test
    void multiRowUpdateIsAllOrNothing() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(12);
        closure(closed);
        int a = event(today.plusDays(10), 9, 10);
        int b = event(today.plusDays(11), 9, 10);
        String before = eventsSnapshot();

        fails("BTC01", () -> exec(
            "UPDATE public.events SET start_time = start_time + interval '1 day', end_time = end_time + interval '1 day' " +
                "WHERE id IN (?, ?)", a, b));

        assertEquals(before, eventsSnapshot());
    }

    @Test
    void upsertCannotMoveAReservationOntoAClosedDay() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(12);
        closure(closed);
        int id = event(today.plusDays(10), 9, 10);

        fails("BTC01", () -> exec(
            "INSERT INTO public.events (id, host_user_id, start_time, end_time, event_type, title, is_public) " +
                "VALUES (?, 2, ?::timestamptz, ?::timestamptz, 'Meeting', 'x', true) " +
                "ON CONFLICT (id) DO UPDATE SET start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time",
            id, at(closed, 9, 0), at(closed, 10, 0)));
    }

    @Test
    void reservationsOutsideEverySemesterBeforeTheMigrationStayEditable() throws Exception {
        // Simulate a legacy row (written before the rules existed) with the triggers off.
        Connection owner = superuser();
        exec(owner, "SET session_replication_role = replica");
        int legacy = eventAt(owner, at(today.plusDays(3), 9, 0), at(today.plusDays(3), 10, 0));
        exec(owner, "SET session_replication_role = origin");

        exec("UPDATE public.events SET title = 'Fixed typo', description = 'x' WHERE id = ?", legacy);
        assertEquals("Fixed typo", text("SELECT title FROM public.events WHERE id = ?", legacy));
        fails("BTC01", () -> exec(
            "UPDATE public.events SET start_time = start_time + interval '1 day', end_time = end_time + interval '1 day' WHERE id = ?",
            legacy));
    }

    // ---- Isolation and bypass attempts ------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"REPEATABLE READ", "SERIALIZABLE"})
    void calendarWritesOutsideReadCommittedAreRefusedAndWriteNothing(String isolation) throws Exception {
        long semesterId = currentSemester();
        long existingClosure = closure(today.plusDays(20));
        int existingEvent = event(today.plusDays(21), 9, 10);
        String eventsBefore = eventsSnapshot();
        String closuresBefore = text("SELECT string_agg(u::text, '|' ORDER BY u.id) FROM public.unavailable_days u");
        String semestersBefore = text("SELECT string_agg(s::text, '|' ORDER BY s.id) FROM public.semesters s");
        db.setAutoCommit(false);

        List<Executable> writes = List.of(
            () -> event(today.plusDays(10), 9, 10),
            () -> bulkInsert(eventsJson(new Instant[][] {{at(today.plusDays(11), 9, 0), at(today.plusDays(11), 10, 0)}})),
            () -> exec("UPDATE public.events SET start_time = start_time + interval '1 day', " +
                "end_time = end_time + interval '1 day' WHERE id = ?", existingEvent),
            () -> closure(today.plusDays(12)),
            () -> exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?",
                today.plusDays(13), existingClosure),
            () -> exec("UPDATE public.semesters SET end_date = end_date - 1 WHERE id = ?", semesterId),
            () -> exec("DELETE FROM public.semesters WHERE id = ?", semesterId),
            () -> scalar("SELECT count(*) FROM public.create_events_with_calendar_override(1, 'x', ?::jsonb)",
                eventsJson(new Instant[][] {{at(today.plusDays(14), 9, 0), at(today.plusDays(14), 10, 0)}})),
            () -> scalar("SELECT count(*) FROM public.update_event_with_calendar_override(1, 'x', ?, ?::jsonb)",
                existingEvent, "{\"title\":\"x\"}"),
            () -> scalar("SELECT count(*) FROM public.create_unavailable_day_with_override(1, 'x', ?::jsonb)",
                "{\"unavailable_date\":\"" + today.plusDays(15) + "\"}"),
            () -> scalar("SELECT count(*) FROM public.update_unavailable_day_with_override(1, 'x', ?::bigint, ?::jsonb)",
                existingClosure, "{\"reason\":\"x\"}")
        );

        for (int i = 0; i < writes.size(); i++) {
            exec("SET TRANSACTION ISOLATION LEVEL " + isolation);
            PSQLException error = fails("0A000", writes.get(i));
            assertEquals("Calendar writes must run under READ COMMITTED isolation.",
                error.getServerErrorMessage().getMessage(), "write #" + i);
            db.rollback();
        }

        // The same refusal applies when the isolation level comes from the session default.
        db.setAutoCommit(true);
        exec("SET default_transaction_isolation = '" + isolation.toLowerCase() + "'");
        fails("0A000", () -> event(today.plusDays(16), 9, 10));
        fails("0A000", () -> closure(today.plusDays(17)));
        exec("RESET default_transaction_isolation");

        assertEquals(eventsBefore, eventsSnapshot(), "no reservation may be written or moved");
        assertEquals(closuresBefore, text("SELECT string_agg(u::text, '|' ORDER BY u.id) FROM public.unavailable_days u"));
        assertEquals(semestersBefore, text("SELECT string_agg(s::text, '|' ORDER BY s.id) FROM public.semesters s"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void readCommittedCalendarWritesAndNonCalendarEditsStillWork() throws Exception {
        currentSemester();
        db.setAutoCommit(false);
        exec("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
        int id = event(today.plusDays(10), 9, 10);
        closure(today.plusDays(11));
        db.commit();

        // A title-only edit never takes the calendar lock, so it works at any isolation level.
        exec("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
        exec("UPDATE public.events SET title = 'Renamed' WHERE id = ?", id);
        db.commit();
        db.setAutoCommit(true);

        assertEquals(1, count("SELECT count(*) FROM public.events"));
        assertEquals(1, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals("Renamed", text("SELECT title FROM public.events WHERE id = ?", id));
    }

    @Test
    void serviceRoleCannotSwitchOffTheTriggers() throws Exception {
        fails("42501", () -> exec("SET session_replication_role = replica"));
        fails("42501", () -> exec("ALTER TABLE public.events DISABLE TRIGGER events_calendar_rules_on_insert"));
        fails("42501", () -> exec("ALTER TABLE public.unavailable_days DISABLE TRIGGER unavailable_days_guard"));
        fails("42501", () -> exec("DROP TRIGGER semesters_guard ON public.semesters"));
    }
}
