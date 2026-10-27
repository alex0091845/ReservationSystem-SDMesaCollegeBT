package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unavailable-day (closure) rules, including emergency closures through the override RPCs,
 * their close_day audit rows, and the live "affected reservations" query.
 */
@Testcontainers(disabledWithoutDocker = true)
class CalendarClosureRulesDbTest extends CalendarDbTestBase {

    private static final String CREATE_RPC =
        "SELECT id FROM public.create_unavailable_day_with_override(?, ?, ?::jsonb)";
    private static final String UPDATE_RPC =
        "SELECT count(*) FROM public.update_unavailable_day_with_override(?, ?, ?::bigint, ?::jsonb)";
    private static final String EVENT_UPDATE_RPC =
        "SELECT count(*) FROM public.update_event_with_calendar_override(?, ?, ?, ?::jsonb)";

    private long emergencyClose(Integer actor, String reason, String dayJson) throws Exception {
        return scalarLong(db, CREATE_RPC, actor, reason, dayJson);
    }

    private static String day(LocalDate date) {
        return "{\"unavailable_date\":\"" + date + "\",\"reason\":\"Emergency\",\"created_by\":1}";
    }

    record AuditRow(long id, Integer eventId, String action, Integer actorId, String actorEmail, String reason,
                    List<String> rules, List<String> dates, Instant start, Instant end) {
    }

    /** Every audit row, in insertion order. */
    private List<AuditRow> audit() throws Exception {
        return audit("TRUE");
    }

    private List<AuditRow> closeDayAudit() throws Exception {
        return audit("action = 'close_day'");
    }

    private List<AuditRow> audit(String where) throws Exception {
        List<AuditRow> rows = new ArrayList<>();
        try (PreparedStatement st = db.prepareStatement(
            "SELECT id, event_id, action, actor_user_id, actor_email, reason, overridden_rules::text[], " +
                "restricted_dates::text[], event_start_time, event_end_time " +
                "FROM public.calendar_override_audit WHERE " + where + " ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                rows.add(new AuditRow(
                    rs.getLong(1), (Integer) rs.getObject(2), rs.getString(3), (Integer) rs.getObject(4),
                    rs.getString(5), rs.getString(6), strings(rs.getArray(7)), strings(rs.getArray(8)),
                    rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant()));
            }
        }
        return rows;
    }

    private static List<String> strings(java.sql.Array array) throws Exception {
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add(String.valueOf(value));
        }
        return values;
    }

    private static List<Integer> eventIds(List<AuditRow> rows) {
        List<Integer> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.eventId()));
        return ids;
    }

    private void assertCloseDayRow(AuditRow row, int eventId, int actor, String email, String reason,
                                   LocalDate closed, Instant start, Instant end) {
        assertEquals(eventId, row.eventId(), row.toString());
        assertEquals("close_day", row.action(), row.toString());
        assertEquals(actor, row.actorId(), row.toString());
        assertEquals(email, row.actorEmail(), row.toString());
        assertEquals(reason, row.reason(), row.toString());
        assertEquals(List.of("existing_reservations"), row.rules(), row.toString());
        assertEquals(List.of(closed.toString()), row.dates(), row.toString());
        assertEquals(start, row.start(), row.toString());
        assertEquals(end, row.end(), row.toString());
    }

    /** Start of the Los Angeles day, computed exactly the way UnavailableDayController does. */
    private static Instant laMidnight(LocalDate date) {
        return date.atStartOfDay(LA).toInstant();
    }

    /**
     * The SQL equivalent of the PostgREST query behind
     * GET /api/unavailable-days/{id}/affected-reservations:
     * {@code events?start_time=lt.<next LA midnight>&end_time=gt.<LA midnight>&order=start_time.asc,id.asc},
     * with the bounds passed as the same Instant strings the controller sends.
     */
    private List<Integer> affectedNow(LocalDate closed) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement st = db.prepareStatement(
            "SELECT id FROM public.events WHERE start_time < ?::timestamptz AND end_time > ?::timestamptz " +
                "ORDER BY start_time ASC, id ASC")) {
            st.setString(1, laMidnight(closed.plusDays(1)).toString());
            st.setString(2, laMidnight(closed).toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }
        return ids;
    }

    private void move(int eventId, Instant start, Instant end) throws Exception {
        exec("UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?",
            start, end, eventId);
    }

    // ---- Plain closure rules ---------------------------------------------------

    @Test
    void pastDatesAreRefusedButTodayIsAllowed() throws Exception {
        currentSemester();

        PSQLException error = fails("BTC05", () -> closure(today.minusDays(1)));

        assertEquals(today.minusDays(1).toString(), detail(error).path("date").asText());
        closure(today);
    }

    @Test
    void closuresMustFallInsideASemesterWhichIsFilledInAutomatically() throws Exception {
        long fall = semester("Fall", today.plusDays(1), today.plusDays(30));
        long spring = semester("Spring", today.plusDays(40), today.plusDays(70));

        PSQLException gap = fails("BTC04", () -> closure(today.plusDays(35)));
        assertEquals(today.plusDays(35).toString(), detail(gap).path("date").asText());

        long id = closure(today.plusDays(10));
        assertEquals(fall, count("SELECT semester_id FROM public.unavailable_days WHERE id = ?", id));

        PSQLException wrong = fails("BTC04", () -> exec(
            "INSERT INTO public.unavailable_days (semester_id, unavailable_date) VALUES (?, ?::date)",
            spring, today.plusDays(11)));
        assertEquals(spring, detail(wrong).path("semester_id").asLong());

        // Moving a closure into another semester re-derives semester_id.
        exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?", today.plusDays(50), id);
        assertEquals(spring, count("SELECT semester_id FROM public.unavailable_days WHERE id = ?", id));
        fails("BTC04", () -> exec("UPDATE public.unavailable_days SET semester_id = ? WHERE id = ?", fall, id));
    }

    @Test
    void duplicateDatesAndOverlongReasonsAreRefused() throws Exception {
        currentSemester();
        closure(today.plusDays(5));

        fails("23505", () -> closure(today.plusDays(5)));
        fails("23514", () -> exec(
            "INSERT INTO public.unavailable_days (unavailable_date, reason) VALUES (?::date, ?)",
            today.plusDays(6), "r".repeat(201)));
    }

    @Test
    void closingADayWithReservationsIsRefusedWithDetailsAndLeavesThemUntouched() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        int morning = event(busy, 9, 10);
        int afternoon = event(busy, 14, 15);
        event(busy.plusDays(1), 9, 10);
        String before = eventsSnapshot();

        PSQLException error = fails("BTC02", () -> closure(busy));

        JsonNode detail = detail(error);
        assertEquals(busy.toString(), detail.path("date").asText());
        JsonNode reservations = detail.path("reservations");
        assertEquals(2, reservations.size());
        assertEquals(morning, reservations.get(0).path("id").asInt());
        assertEquals(afternoon, reservations.get(1).path("id").asInt());
        assertEquals("Lab", reservations.get(0).path("title").asText());
        assertEquals(FACULTY, reservations.get(0).path("host_user_id").asInt());
        assertTrue(reservations.get(0).hasNonNull("start_time"));
        assertTrue(reservations.get(0).hasNonNull("end_time"));
        assertEquals(before, eventsSnapshot(), "reservations must not be modified");
        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
    }

    @Test
    void movingAClosureIsJudgedLikeANewOne() throws Exception {
        currentSemester();
        long id = closure(today.plusDays(5));
        event(today.plusDays(6), 9, 10);

        fails("BTC02", () -> exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?",
            today.plusDays(6), id));
        fails("BTC05", () -> exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?",
            today.minusDays(1), id));
        fails("BTC04", () -> exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?",
            today.plusDays(330), id));
        exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?", today.plusDays(7), id);
    }

    @Test
    void reasonEditOfAPastClosureIsAllowed() throws Exception {
        currentSemester();
        Connection owner = superuser();
        exec(owner, "SET session_replication_role = replica");
        long past = scalarLong(owner,
            "INSERT INTO public.unavailable_days (semester_id, unavailable_date, reason) " +
                "SELECT id, ?::date, 'Old' FROM public.semesters RETURNING id", today.minusDays(3));
        exec(owner, "SET session_replication_role = origin");

        exec("UPDATE public.unavailable_days SET reason = 'Corrected' WHERE id = ?", past);

        assertEquals("Corrected", text("SELECT reason FROM public.unavailable_days WHERE id = ?", past));
    }

    @Test
    void unavailableDaysStoreNoOverrideRecordOrAffectedList() throws Exception {
        assertEquals(
            List.of("id", "semester_id", "unavailable_date", "reason", "created_by", "created_at", "updated_at"),
            columns("unavailable_days"));

        currentSemester();
        fails("42703", () -> exec(
            "INSERT INTO public.unavailable_days (unavailable_date, override_by) VALUES (?::date, 1)",
            today.plusDays(5)));
        fails("42703", () -> exec(
            "INSERT INTO public.unavailable_days (unavailable_date, affected_reservation_ids) VALUES (?::date, '{1}')",
            today.plusDays(5)));
    }

    private List<String> columns(String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement st = db.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? " +
                "ORDER BY ordinal_position")) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    @Test
    void directWritesCannotAlterTheAuthorOrCreationTimeAndForgeNoOverride() throws Exception {
        currentSemester();
        long id = scalarLong(db,
            "INSERT INTO public.unavailable_days (unavailable_date) VALUES (?::date) RETURNING id", today.plusDays(5));

        exec("UPDATE public.unavailable_days SET created_by = 2, created_at = '2000-01-01' WHERE id = ?", id);

        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days WHERE created_by IS NOT NULL OR created_at < '2001-01-01'"));

        // A plain insert on a busy day is refused and leaves no audit trail.
        event(today.plusDays(8), 9, 10);
        fails("BTC02", () -> exec(
            "INSERT INTO public.unavailable_days (unavailable_date, reason) VALUES (?::date, 'x')", today.plusDays(8)));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void auditActionsAreLimitedToCreateUpdateAndCloseDay() throws Exception {
        String definition = text(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'calendar_override_audit_action_check'");
        for (String action : new String[] {"'create'", "'update'", "'close_day'"}) {
            assertTrue(definition.contains(action), definition);
        }
        fails("23514", () -> exec(
            "INSERT INTO public.calendar_override_audit (action, actor_email, reason, overridden_rules, restricted_dates, " +
                "event_start_time, event_end_time) VALUES ('delete', 'x', 'x', '{}', '{}', now(), now())"));
        fails("23514", () -> exec(
            "INSERT INTO public.calendar_override_audit (action, actor_email, reason, overridden_rules, restricted_dates, " +
                "event_start_time, event_end_time) VALUES ('CLOSE_DAY', 'x', 'x', '{}', '{}', now(), now())"));
    }

    // ---- Emergency closures ----------------------------------------------------

    @Test
    void emergencyClosureKeepsReservationsAndAuditsEachOne() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        int afternoon = event(busy, 14, 15);
        int morning = event(busy, 9, 10);
        event(busy.plusDays(1), 9, 10);
        String before = eventsSnapshot();

        long id = emergencyClose(ADMIN, "  Burst pipe  ",
            "{\"unavailable_date\":\"" + busy + "\",\"reason\":\"Flood\",\"created_by\":1," +
                "\"override_by\":2,\"override_reason\":\"forged\",\"affected_reservation_ids\":[999],\"id\":777}");

        assertNotEquals(777, id);
        assertEquals("Flood", text("SELECT reason FROM public.unavailable_days WHERE id = ?", id));
        assertEquals(busy.toString(), text("SELECT unavailable_date::text FROM public.unavailable_days WHERE id = ?", id));
        assertEquals(before, eventsSnapshot(), "kept reservations must be untouched");

        // One close_day row per affected reservation, and nothing else.
        List<AuditRow> rows = audit();
        assertEquals(2, rows.size(), rows.toString());
        rows.sort((a, b) -> a.start().compareTo(b.start()));
        assertCloseDayRow(rows.get(0), morning, ADMIN, CalendarDatabase.ADMIN_EMAIL, "Burst pipe", busy,
            at(busy, 9, 0), at(busy, 10, 0));
        assertCloseDayRow(rows.get(1), afternoon, ADMIN, CalendarDatabase.ADMIN_EMAIL, "Burst pipe", busy,
            at(busy, 14, 0), at(busy, 15, 0));
        assertEquals(List.of(morning, afternoon), affectedNow(busy));

        // New bookings on the closed day are still refused, even by the same admin without an override.
        fails("BTC01", () -> event(busy, 11, 12));
        // Title-only and same-day time edits of the kept reservations still work and are not audited.
        exec("UPDATE public.events SET title = 'Moved online' WHERE id = ?", morning);
        move(afternoon, at(busy, 15, 0), at(busy, 16, 0));
        // Moving a kept reservation to an open day works; moving it back does not.
        move(morning, at(busy.plusDays(2), 9, 0), at(busy.plusDays(2), 10, 0));
        fails("BTC01", () -> move(morning, at(busy, 9, 0), at(busy, 10, 0)));
        // A reason-only edit of the closure does not re-audit.
        exec("UPDATE public.unavailable_days SET reason = 'Flood (update)' WHERE id = ?", id);
        assertEquals(1, count(UPDATE_RPC, ADMIN, "Note", id, "{\"reason\":\"Flood (again)\"}"));

        assertEquals(2, audit().size(), "edits must not add or remove audit rows: " + audit());
        assertEquals(List.of(afternoon), affectedNow(busy));
    }

    @Test
    void emergencyClosureOnAFreeDayWritesNoAuditRow() throws Exception {
        currentSemester();
        event(today.plusDays(8), 9, 10);
        event(today.plusDays(10), 9, 10);

        long id = emergencyClose(ADMIN, "Precaution", day(today.plusDays(9)));

        assertTrue(id > 0);
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
        assertEquals(List.of(), affectedNow(today.plusDays(9)));
    }

    @Test
    void emergencyClosureDoesNotLeakIntoLaterStatementsOfTheSameTransaction() throws Exception {
        currentSemester();
        event(today.plusDays(9), 9, 10);
        event(today.plusDays(10), 9, 10);
        db.setAutoCommit(false);

        emergencyClose(ADMIN, "Gas leak", day(today.plusDays(9)));
        fails("BTC02", () -> closure(today.plusDays(10)));
        db.rollback();
        db.setAutoCommit(true);

        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void emergencyClosureStillObeysPastSemesterAndDuplicateRules() throws Exception {
        currentSemester();
        event(today.plusDays(4), 9, 10);
        closure(today.plusDays(5));

        fails("BTC05", () -> emergencyClose(ADMIN, "x", day(today.minusDays(1))));
        fails("BTC04", () -> emergencyClose(ADMIN, "x", day(today.plusDays(330))));
        fails("23505", () -> emergencyClose(ADMIN, "x", day(today.plusDays(5))));
        assertEquals(1, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void emergencyClosureOfABusyDayInThePastOrOutsideASemesterIsStillRefused() throws Exception {
        LocalDate end = today.plusDays(30);
        semester("Fall", today.minusDays(10), end);
        // A reservation yesterday and one the day after the semester (admin override, so it exists).
        int yesterday = event(today.minusDays(1), 9, 10);
        scalar(db, "SELECT count(*) FROM public.create_events_with_calendar_override(?, ?, ?::jsonb)", ADMIN, "Bridge",
            eventsJson(new Instant[][] {{at(end.plusDays(1), 9, 0), at(end.plusDays(1), 10, 0)}}));
        long auditBefore = count("SELECT count(*) FROM public.calendar_override_audit");

        fails("BTC05", () -> emergencyClose(ADMIN, "x", day(today.minusDays(1))));
        fails("BTC04", () -> emergencyClose(ADMIN, "x", day(end.plusDays(1))));

        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals(auditBefore, count("SELECT count(*) FROM public.calendar_override_audit"));
        assertTrue(yesterday > 0);
    }

    @Test
    void emergencyClosureIsRefusedForNonAdminsAndBlankReasons() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        event(busy, 9, 10);

        fails("BTA01", () -> emergencyClose(FACULTY, "Please", day(busy)));
        fails("BTA01", () -> emergencyClose(DISABLED_ADMIN, "Please", day(busy)));
        fails("BTA01", () -> emergencyClose(9999, "Please", day(busy)));
        fails("BTA01", () -> emergencyClose(null, "Please", day(busy)));
        fails("22023", () -> emergencyClose(ADMIN, "   ", day(busy)));
        fails("22023", () -> emergencyClose(ADMIN, null, day(busy)));
        fails("22023", () -> emergencyClose(ADMIN, "r".repeat(501), day(busy)));
        fails("22023", () -> emergencyClose(ADMIN, "x", "[]"));
        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void emergencyMoveThroughTheUpdateRpcAuditsTheNewDayAndKeepsEarlierRows() throws Exception {
        currentSemester();
        LocalDate free = today.plusDays(5);
        LocalDate busy = today.plusDays(9);
        int reservation = event(busy, 9, 10);
        long id = closure(free);

        fails("BTC02", () -> exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?", busy, id));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));

        assertEquals(1, count(UPDATE_RPC, SECOND_ADMIN, " Boiler failure ", id,
            "{\"unavailable_date\":\"" + busy + "\",\"override_by\":2,\"affected_reservation_ids\":[5]," +
                "\"created_by\":2,\"id\":4242}"));

        assertEquals(busy.toString(), text("SELECT unavailable_date::text FROM public.unavailable_days WHERE id = ?", id));
        assertEquals(1, count("SELECT created_by FROM public.unavailable_days WHERE id = ?", id));
        assertEquals(1, count("SELECT count(*) FROM public.unavailable_days WHERE id = ?", id));
        List<AuditRow> rows = audit();
        assertEquals(1, rows.size(), rows.toString());
        assertCloseDayRow(rows.get(0), reservation, SECOND_ADMIN, "second-admin@sdccd.edu", "Boiler failure", busy,
            at(busy, 9, 0), at(busy, 10, 0));

        // Moving it to a free day (plain update) adds nothing and removes nothing.
        exec("UPDATE public.unavailable_days SET unavailable_date = ?::date WHERE id = ?", today.plusDays(12), id);
        assertEquals(rows, audit());

        // Through the RPC as well: a busy target is audited again, a free one is not.
        assertEquals(1, count(UPDATE_RPC, ADMIN, "Again", id, "{\"unavailable_date\":\"" + busy + "\"}"));
        assertEquals(1, count(UPDATE_RPC, ADMIN, "Back", id, "{\"unavailable_date\":\"" + today.plusDays(13) + "\"}"));
        List<AuditRow> after = audit();
        assertEquals(2, after.size(), after.toString());
        assertEquals(rows.get(0), after.get(0));
        assertCloseDayRow(after.get(1), reservation, ADMIN, CalendarDatabase.ADMIN_EMAIL, "Again", busy,
            at(busy, 9, 0), at(busy, 10, 0));
    }

    @Test
    void updateRpcReturnsNothingForAMissingIdAndIsRefusedForFaculty() throws Exception {
        currentSemester();
        long id = closure(today.plusDays(5));
        event(today.plusDays(9), 9, 10);

        assertEquals(0, count(UPDATE_RPC, ADMIN, "x", 999999L, "{\"reason\":\"y\"}"));
        fails("BTA01", () -> count(UPDATE_RPC, FACULTY, "x", id,
            "{\"unavailable_date\":\"" + today.plusDays(9) + "\"}"));
        fails("BTA01", () -> count(UPDATE_RPC, DISABLED_ADMIN, "x", id,
            "{\"unavailable_date\":\"" + today.plusDays(9) + "\"}"));
        fails("22023", () -> count(UPDATE_RPC, ADMIN, " ", id,
            "{\"unavailable_date\":\"" + today.plusDays(9) + "\"}"));
        fails("BTC05", () -> count(UPDATE_RPC, ADMIN, "x", id,
            "{\"unavailable_date\":\"" + today.minusDays(2) + "\"}"));
        fails("BTC04", () -> count(UPDATE_RPC, ADMIN, "x", id,
            "{\"unavailable_date\":\"" + today.plusDays(330) + "\"}"));
        assertEquals(today.plusDays(5).toString(),
            text("SELECT unavailable_date::text FROM public.unavailable_days WHERE id = ?", id));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void forgedOverrideSettingsCannotCloseABusyDay() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        event(busy, 9, 10);
        db.setAutoCommit(false);

        for (String actor : new String[] {String.valueOf(FACULTY), String.valueOf(DISABLED_ADMIN), "9999"}) {
            exec("SELECT set_config('calendar.override_actor_id', ?, true)", actor);
            exec("SELECT set_config('calendar.override_reason', 'forged', true)");
            fails("BTA01", () -> closure(busy));
            db.rollback();
        }

        exec("SELECT set_config('calendar.override_actor_id', ?, true)", String.valueOf(ADMIN));
        fails("BTA01", () -> closure(busy));
        db.rollback();
        db.setAutoCommit(true);
        assertEquals(0, count("SELECT count(*) FROM public.unavailable_days"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void closeDayAuditCoversEveryOverlappingBookingAndNotTheMidnightNeighbours() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        int third = event(busy, 15, 16);
        int first = eventAt(at(busy.minusDays(1), 23, 0), at(busy, 1, 0));
        int second = event(busy, 9, 10);
        int last = eventAt(at(busy, 23, 0), at(busy.plusDays(1), 1, 0));
        int endsAtMidnight = eventAt(at(busy.minusDays(1), 22, 0), at(busy, 0, 0));
        int startsAtNextMidnight = eventAt(at(busy.plusDays(1), 0, 0), at(busy.plusDays(1), 1, 0));

        emergencyClose(ADMIN, "Power outage", day(busy));

        List<AuditRow> rows = closeDayAudit();
        List<Integer> audited = new ArrayList<>(eventIds(rows));
        audited.sort(null);
        List<Integer> expected = new ArrayList<>(List.of(first, second, third, last));
        expected.sort(null);
        assertEquals(expected, audited, rows.toString());
        assertFalse(audited.contains(endsAtMidnight));
        assertFalse(audited.contains(startsAtNextMidnight));
        for (AuditRow row : rows) {
            assertEquals("Power outage", row.reason());
            assertEquals(List.of(busy.toString()), row.dates());
        }
        // The endpoint's live query lists the same reservations, in start order.
        assertEquals(List.of(first, second, third, last), affectedNow(busy));
    }

    // ---- Live affected-reservations query vs the audit trail ----------------------

    /**
     * The affected list is computed from events on every call, so a reservation that is moved
     * away or deleted disappears from it (and one moved onto the day by an admin override
     * appears), while the close_day audit rows written at closure time stay exactly as they were.
     */
    @Test
    void affectedListFollowsTheEventsWhileCloseDayAuditRowsSurvive() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        int morning = event(busy, 9, 10);
        int afternoon = event(busy, 14, 15);
        int overnight = eventAt(at(busy.minusDays(1), 23, 0), at(busy, 1, 0));
        int elsewhere = event(busy.plusDays(3), 9, 10);

        emergencyClose(ADMIN, "Roof leak", day(busy));
        List<AuditRow> original = closeDayAudit();
        assertEquals(3, original.size(), original.toString());
        assertEquals(List.of(overnight, morning, afternoon), affectedNow(busy));

        // Reschedule the morning booking to another day.
        move(morning, at(busy.plusDays(2), 9, 0), at(busy.plusDays(2), 10, 0));
        assertEquals(List.of(overnight, afternoon), affectedNow(busy));
        assertEquals(original, closeDayAudit(), "moving a reservation must not touch its close_day row");

        // Delete the afternoon booking: its audit row stays, only the event link is cleared.
        exec("DELETE FROM public.events WHERE id = ?", afternoon);
        assertEquals(List.of(overnight), affectedNow(busy));
        List<AuditRow> afterDelete = closeDayAudit();
        assertEquals(3, afterDelete.size(), afterDelete.toString());
        for (int i = 0; i < original.size(); i++) {
            AuditRow before = original.get(i);
            AuditRow now = afterDelete.get(i);
            if (before.eventId() == afternoon) {
                assertNull(now.eventId(), "deleted reservation's audit row keeps no dangling id");
                assertEquals(new AuditRow(before.id(), null, before.action(), before.actorId(), before.actorEmail(),
                    before.reason(), before.rules(), before.dates(), before.start(), before.end()), now);
                assertCloseDayRow(new AuditRow(now.id(), afternoon, now.action(), now.actorId(), now.actorEmail(),
                        now.reason(), now.rules(), now.dates(), now.start(), now.end()),
                    afternoon, ADMIN, CalendarDatabase.ADMIN_EMAIL, "Roof leak", busy, at(busy, 14, 0), at(busy, 15, 0));
            } else {
                assertEquals(before, now);
            }
        }

        // An admin override moves another booking onto the closed day: it now appears in the
        // live list, and the move is audited as an 'update', not as close_day.
        assertEquals(1, count(EVENT_UPDATE_RPC, ADMIN, "Relocated", elsewhere,
            "{\"start_time\":\"" + at(busy, 12, 0) + "\",\"end_time\":\"" + at(busy, 13, 0) + "\"}"));
        assertEquals(List.of(overnight, elsewhere), affectedNow(busy));
        assertEquals(3, closeDayAudit().size());
        List<AuditRow> updates = audit("action = 'update'");
        assertEquals(1, updates.size(), updates.toString());
        assertEquals(elsewhere, updates.get(0).eventId());
        assertEquals(List.of("unavailable_day"), updates.get(0).rules());

        // Deleting everything leaves an empty list and the full trail.
        exec("DELETE FROM public.events WHERE id IN (?, ?)", overnight, elsewhere);
        assertEquals(List.of(), affectedNow(busy));
        assertEquals(4, audit().size());
        assertEquals(3, count("SELECT count(*) FROM public.calendar_override_audit WHERE action = 'close_day' " +
            "AND reason = 'Roof leak' AND restricted_dates = ARRAY[?::date]", busy));
    }

    @Test
    void deletingTheClosureKeepsItsCloseDayAuditRows() throws Exception {
        currentSemester();
        LocalDate busy = today.plusDays(9);
        event(busy, 9, 10);
        long id = emergencyClose(ADMIN, "Fire alarm", day(busy));
        List<AuditRow> rows = closeDayAudit();

        exec("DELETE FROM public.unavailable_days WHERE id = ?", id);

        assertEquals(rows, closeDayAudit());
        fails("42501", () -> exec("DELETE FROM public.calendar_override_audit WHERE action = 'close_day'"));
        fails("42501", () -> exec("UPDATE public.calendar_override_audit SET restricted_dates = '{}' WHERE action = 'close_day'"));
        assertEquals(rows, closeDayAudit());
    }

    /**
     * On both DST change days the Los Angeles day is 23 or 25 hours long. The bounds the
     * controller computes must match Postgres' own America/Los_Angeles midnights, and the
     * live list must agree with the close_day rows the database wrote.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void affectedListAndAuditAgreeOnDstChangeDays(boolean fallBack) throws Exception {
        LocalDate change = nextDstChange(fallBack);
        semester("DST", change.minusDays(3), change.plusDays(3));

        assertEquals(laMidnight(change), ((java.sql.Timestamp) scalar(
            "SELECT (?::date)::timestamp AT TIME ZONE 'America/Los_Angeles'", change)).toInstant());
        assertEquals(laMidnight(change.plusDays(1)), ((java.sql.Timestamp) scalar(
            "SELECT (?::date + 1)::timestamp AT TIME ZONE 'America/Los_Angeles'", change)).toInstant());
        assertEquals(Duration.ofHours(fallBack ? 25 : 23),
            Duration.between(laMidnight(change), laMidnight(change.plusDays(1))));

        int endsAtMidnight = eventAt(at(change.minusDays(1), 23, 0), at(change, 0, 0));
        int early = eventAt(at(change, 0, 30), at(change, 1, 30));
        int late = eventAt(at(change, 22, 30), at(change, 23, 59));
        int startsAtNextMidnight = eventAt(at(change.plusDays(1), 0, 0), at(change.plusDays(1), 1, 0));
        // An instant in the last hour of the 25-hour day / just before the 23-hour day ends.
        int lastHour = eventAt(laMidnight(change.plusDays(1)).minusSeconds(1800), laMidnight(change.plusDays(1)));

        emergencyClose(ADMIN, "DST check", day(change));

        List<Integer> live = affectedNow(change);
        assertEquals(List.of(early, late, lastHour), live);
        List<Integer> audited = new ArrayList<>(eventIds(closeDayAudit()));
        audited.sort(null);
        List<Integer> sortedLive = new ArrayList<>(live);
        sortedLive.sort(null);
        assertEquals(sortedLive, audited, "live list and close_day audit disagree on a DST day");
        assertFalse(live.contains(endsAtMidnight));
        assertFalse(live.contains(startsAtNextMidnight));
    }
}
