package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admin overrides for reservations (both RPC functions), the audit trail, forged
 * override settings, and what anon/authenticated can reach.
 */
@Testcontainers(disabledWithoutDocker = true)
class CalendarOverrideDbTest extends CalendarDbTestBase {

    private static final String CREATE_RPC =
        "SELECT count(*) FROM public.create_events_with_calendar_override(?, ?, ?::jsonb)";
    private static final String UPDATE_RPC =
        "SELECT count(*) FROM public.update_event_with_calendar_override(?, ?, ?, ?::jsonb)";

    private static final String[] NEW_TABLES = {"semesters", "unavailable_days", "calendar_override_audit"};
    private static final String[] NEW_FUNCTIONS = {
        "calendar_local_date", "calendar_event_dates", "calendar_lock", "calendar_lock_statement",
        "calendar_event_violations", "calendar_begin_override", "calendar_end_override",
        "calendar_override_admin", "events_enforce_calendar_rules", "unavailable_days_guard",
        "semesters_guard", "create_events_with_calendar_override", "update_event_with_calendar_override",
        "create_unavailable_day_with_override", "update_unavailable_day_with_override"
    };

    record AuditRow(Integer eventId, String action, Integer actorId, String actorEmail, String reason,
                    List<Object> rules, List<String> dates, Instant start, Instant end) {
    }

    private List<AuditRow> audit() throws Exception {
        List<AuditRow> rows = new ArrayList<>();
        try (PreparedStatement st = db.prepareStatement(
            "SELECT event_id, action, actor_user_id, actor_email, reason, overridden_rules, " +
                "restricted_dates::text[], event_start_time, event_end_time FROM public.calendar_override_audit ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                List<String> dates = new ArrayList<>();
                for (Object d : (Object[]) rs.getArray(7).getArray()) {
                    dates.add(d.toString());
                }
                rows.add(new AuditRow(
                    (Integer) rs.getObject(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                    rs.getString(5), List.of((Object[]) rs.getArray(6).getArray()), dates,
                    rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant()));
            }
        }
        return rows;
    }

    private static Instant[] range(LocalDate day, int from, int to) {
        return new Instant[] {at(day, from, 0), at(day, to, 0)};
    }

    // ---- create_events_with_calendar_override ------------------------------------

    @Test
    void overrideOnAClosedDayIsSavedAndAudited() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);

        assertEquals(1, count(CREATE_RPC, ADMIN, "  Accreditation visit  ",
            eventsJson(new Instant[][] {range(closed, 9, 10)})));

        int eventId = (int) count("SELECT id FROM public.events");
        List<AuditRow> audit = audit();
        assertEquals(1, audit.size());
        AuditRow row = audit.get(0);
        assertEquals(eventId, row.eventId());
        assertEquals("create", row.action());
        assertEquals(ADMIN, row.actorId());
        assertEquals(CalendarDatabase.ADMIN_EMAIL, row.actorEmail());
        assertEquals("Accreditation visit", row.reason());
        assertEquals(List.of("unavailable_day"), row.rules());
        assertEquals(List.of(closed.toString()), row.dates());
        assertEquals(at(closed, 9, 0), row.start());
        assertEquals(at(closed, 10, 0), row.end());
    }

    @Test
    void overrideBatchIsSavedWholeAndOnlyBypassingRowsAreAudited() throws Exception {
        LocalDate end = today.plusDays(60);
        semester("Fall", today.plusDays(1), end);
        LocalDate closed = today.plusDays(10);
        closure(closed);

        assertEquals(3, count(CREATE_RPC, ADMIN, "Finals",
            eventsJson(new Instant[][] {range(today.plusDays(9), 9, 10), range(closed, 9, 10), range(end.plusDays(3), 9, 10)})));

        List<AuditRow> audit = audit();
        assertEquals(2, audit.size(), audit.toString());
        assertEquals(List.of("unavailable_day"), audit.get(0).rules());
        assertEquals(List.of("outside_semester"), audit.get(1).rules());
        assertEquals(List.of(end.plusDays(3).toString()), audit.get(1).dates());
        assertEquals(3, count("SELECT count(*) FROM public.events"));
    }

    @Test
    void overrideWithNothingToBypassWritesNoAuditRow() throws Exception {
        currentSemester();

        assertEquals(1, count(CREATE_RPC, ADMIN, "Just in case", eventsJson(new Instant[][] {range(today.plusDays(9), 9, 10)})));

        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void overrideWithNoSemesterAtAllIsAuditedAsOutsideSemester() throws Exception {
        LocalDate day = today.plusDays(9);

        count(CREATE_RPC, SECOND_ADMIN, "Bootstrap", eventsJson(new Instant[][] {range(day, 9, 10)}));

        AuditRow row = audit().get(0);
        assertEquals(SECOND_ADMIN, row.actorId());
        assertEquals(List.of("outside_semester"), row.rules());
    }

    @ParameterizedTest
    @ValueSource(ints = {FACULTY, DISABLED_ADMIN, 9999})
    void overrideIsRefusedForAnyoneButAnEnabledAdmin(int actor) throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);

        fails("BTA01", () -> count(CREATE_RPC, actor, "Please", eventsJson(new Instant[][] {range(closed, 9, 10)})));
        // Refused even when nothing would need bypassing.
        fails("BTA01", () -> count(CREATE_RPC, actor, "Please",
            eventsJson(new Instant[][] {range(today.plusDays(9), 9, 10)})));

        assertEquals(0, count("SELECT count(*) FROM public.events"));
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    @Test
    void overrideNeedsAReasonAndAtLeastOneReservation() throws Exception {
        currentSemester();
        String rows = eventsJson(new Instant[][] {range(today.plusDays(9), 9, 10)});

        fails("22023", () -> count(CREATE_RPC, ADMIN, "", rows));
        fails("22023", () -> count(CREATE_RPC, ADMIN, " \t ".replace("\t", " "), rows));
        fails("22023", () -> count(CREATE_RPC, ADMIN, null, rows));
        fails("22023", () -> count(CREATE_RPC, ADMIN, "r".repeat(501), rows));
        fails("22023", () -> count(CREATE_RPC, ADMIN, "x", "[]"));
        fails("22023", () -> count(CREATE_RPC, ADMIN, "x", "{}"));
        count(CREATE_RPC, ADMIN, "r".repeat(500), rows);
        assertEquals(1, count("SELECT count(*) FROM public.events"));
    }

    @Test
    void overrideDoesNotLeakIntoLaterStatementsOfTheSameTransaction() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);
        db.setAutoCommit(false);

        count(CREATE_RPC, ADMIN, "One-off", eventsJson(new Instant[][] {range(closed, 9, 10)}));
        fails("BTC01", () -> event(closed, 11, 12));
        db.rollback();
        db.setAutoCommit(true);
    }

    // ---- update_event_with_calendar_override --------------------------------------

    @Test
    void overrideUpdateMovesOntoAClosedDayAndIsAudited() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        int id = event(today.plusDays(9), 9, 10);
        closure(closed);

        assertEquals(1, count(UPDATE_RPC, ADMIN, "Room swap", id,
            "{\"id\":4242,\"title\":\"Moved\",\"start_time\":\"" + at(closed, 13, 0) +
                "\",\"end_time\":\"" + at(closed, 14, 0) + "\"}"));

        assertEquals("Moved", text("SELECT title FROM public.events WHERE id = ?", id));
        assertEquals(0, count("SELECT count(*) FROM public.events WHERE id = 4242"));
        AuditRow row = audit().get(0);
        assertEquals("update", row.action());
        assertEquals(id, row.eventId());
        assertEquals(List.of(closed.toString()), row.dates());
        assertEquals(at(closed, 13, 0), row.start());

        // Title-only change of a reservation on a closed day bypasses nothing: no new audit row.
        count(UPDATE_RPC, ADMIN, "Typo", id, "{\"title\":\"Moved again\"}");
        assertEquals(1, audit().size());
    }

    @Test
    void overrideUpdateOfAMissingReservationReturnsNothing() throws Exception {
        currentSemester();

        assertEquals(0, count(UPDATE_RPC, ADMIN, "x", 999999, "{\"title\":\"y\"}"));
        fails("BTA01", () -> count(UPDATE_RPC, FACULTY, "x", 999999, "{\"title\":\"y\"}"));
        fails("22023", () -> count(UPDATE_RPC, ADMIN, "x", 999999, "[]"));
    }

    @Test
    void overrideUpdateIsRefusedForFacultyAndDisabledAdmins() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        int id = event(today.plusDays(9), 9, 10);
        closure(closed);
        String move = "{\"start_time\":\"" + at(closed, 13, 0) + "\",\"end_time\":\"" + at(closed, 14, 0) + "\"}";
        String before = eventsSnapshot();

        fails("BTA01", () -> count(UPDATE_RPC, FACULTY, "x", id, move));
        fails("BTA01", () -> count(UPDATE_RPC, DISABLED_ADMIN, "x", id, move));
        fails("22023", () -> count(UPDATE_RPC, ADMIN, "  ", id, move));

        assertEquals(before, eventsSnapshot());
        assertEquals(0, count("SELECT count(*) FROM public.calendar_override_audit"));
    }

    // ---- Forged override settings -------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"2", "3", "9999", "-1"})
    void forgedActorSettingIsRefused(String actor) throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);
        db.setAutoCommit(false);

        exec("SET LOCAL calendar.override_actor_id = '" + actor + "'");
        exec("SET LOCAL calendar.override_reason = 'forged'");
        fails("BTA01", () -> event(closed, 9, 10));
        db.rollback();
        db.setAutoCommit(true);
        assertEquals(0, count("SELECT count(*) FROM public.events"));
    }

    @Test
    void forgedSettingWithoutAReasonOrWithGarbageIsRefused() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);
        db.setAutoCommit(false);

        exec("SET LOCAL calendar.override_actor_id = '1'");
        fails("BTA01", () -> event(closed, 9, 10));
        db.rollback();

        exec("SET LOCAL calendar.override_actor_id = '1'");
        exec("SET LOCAL calendar.override_reason = '   '");
        fails("BTA01", () -> event(closed, 9, 10));
        db.rollback();

        exec("SET LOCAL calendar.override_actor_id = '1; DROP TABLE x'");
        exec("SET LOCAL calendar.override_reason = 'forged'");
        PSQLException garbage = assertThrows(PSQLException.class, () -> event(closed, 9, 10));
        assertNotNull(garbage.getSQLState());
        db.rollback();
        db.setAutoCommit(true);
        assertEquals(0, count("SELECT count(*) FROM public.events"));
    }

    /**
     * Characterisation, reported as a design limit rather than a defect: with raw SQL as
     * service_role, a transaction-local setting naming a real enabled admin is honoured,
     * exactly as calling calendar_begin_override() (granted to service_role) would be.
     * PostgREST cannot chain such statements, so this is reachable only with direct SQL.
     */
    @Test
    void settingNamingAnEnabledAdminIsHonouredAndAttributedToThatAdmin() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);
        db.setAutoCommit(false);

        exec("SET LOCAL calendar.override_actor_id = '" + ADMIN + "'");
        exec("SET LOCAL calendar.override_reason = 'direct sql'");
        event(closed, 9, 10);
        db.commit();
        db.setAutoCommit(true);

        AuditRow row = audit().get(0);
        assertEquals(ADMIN, row.actorId());
        assertEquals("direct sql", row.reason());
    }

    // ---- Audit trail integrity ----------------------------------------------------

    @Test
    void auditIsAppendOnlyForServiceRole() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);
        count(CREATE_RPC, ADMIN, "Audit me", eventsJson(new Instant[][] {range(closed, 9, 10)}));

        fails("42501", () -> exec("UPDATE public.calendar_override_audit SET reason = 'edited'"));
        fails("42501", () -> exec("DELETE FROM public.calendar_override_audit"));
        fails("42501", () -> exec("TRUNCATE public.calendar_override_audit"));
        fails("42501", () -> exec("ALTER TABLE public.calendar_override_audit DISABLE ROW LEVEL SECURITY"));
        assertEquals("Audit me", text("SELECT reason FROM public.calendar_override_audit"));
    }

    @Test
    void auditSurvivesDeletionOfTheReservationAndTheAdmin() throws Exception {
        currentSemester();
        LocalDate closed = today.plusDays(10);
        closure(closed);
        count(CREATE_RPC, SECOND_ADMIN, "Keep the trail", eventsJson(new Instant[][] {range(closed, 9, 10)}));
        int eventId = (int) count("SELECT id FROM public.events");

        exec("DELETE FROM public.events WHERE id = ?", eventId);
        exec("DELETE FROM public.users WHERE id = ?", SECOND_ADMIN);

        AuditRow row = audit().get(0);
        assertNull(row.eventId());
        assertNull(row.actorId());
        assertEquals("second-admin@sdccd.edu", row.actorEmail());
        assertEquals("Keep the trail", row.reason());
    }

    // ---- anon / authenticated ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"anon", "authenticated"})
    void clientRolesCannotReadOrWriteTheNewTables(String role) throws Exception {
        currentSemester();
        closure(today.plusDays(5));
        Connection client = as(role);

        for (String table : NEW_TABLES) {
            fails("42501", () -> exec(client, "SELECT * FROM public." + table));
            fails("42501", () -> exec(client, "DELETE FROM public." + table));
            String column = table.equals("semesters") ? "name" : "reason";
            fails("42501", () -> exec(client, "UPDATE public." + table + " SET " + column + " = " + column));
        }
        fails("42501", () -> exec(client,
            "INSERT INTO public.semesters (name, start_date, end_date) VALUES ('x', current_date, current_date)"));
        fails("42501", () -> exec(client, "INSERT INTO public.unavailable_days (unavailable_date) VALUES (current_date + 1)"));
        fails("42501", () -> exec(client,
            "INSERT INTO public.calendar_override_audit (action, actor_email, reason, overridden_rules, restricted_dates, " +
                "event_start_time, event_end_time) VALUES ('create', 'x', 'x', '{}', '{}', now(), now())"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anon", "authenticated"})
    void clientRolesCannotCallTheNewFunctions(String role) throws Exception {
        currentSemester();
        Connection client = as(role);

        fails("42501", () -> exec(client, "SELECT * FROM public.create_events_with_calendar_override(1, 'x', '[]'::jsonb)"));
        fails("42501", () -> exec(client, "SELECT * FROM public.update_event_with_calendar_override(1, 'x', 1, '{}'::jsonb)"));
        fails("42501", () -> exec(client, "SELECT * FROM public.create_unavailable_day_with_override(1, 'x', '{}'::jsonb)"));
        fails("42501", () -> exec(client, "SELECT * FROM public.update_unavailable_day_with_override(1, 'x', 1, '{}'::jsonb)"));
        fails("42501", () -> exec(client, "SELECT public.calendar_begin_override(1, 'x')"));
        fails("42501", () -> exec(client, "SELECT public.calendar_lock()"));
        fails("42501", () -> exec(client, "SELECT public.calendar_local_date(now())"));

        List<String> executable = new ArrayList<>();
        try (PreparedStatement st = db.prepareStatement(
            "SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace " +
                "AND p.proname = ANY (?) AND has_function_privilege(?, p.oid, 'EXECUTE')")) {
            st.setArray(1, db.createArrayOf("text", NEW_FUNCTIONS));
            st.setString(2, role);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    executable.add(rs.getString(1));
                }
            }
        }
        assertEquals(List.of(), executable, role + " can execute new functions");
        assertEquals(NEW_FUNCTIONS.length,
            count("SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND proname = ANY (?)",
                db.createArrayOf("text", NEW_FUNCTIONS)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anon", "authenticated"})
    void clientRolesHaveNoPrivilegeOnTheNewIdentitySequences(String role) throws Exception {
        List<String> reachable = new ArrayList<>();
        try (PreparedStatement st = db.prepareStatement(
            "SELECT c.relname FROM pg_class c JOIN pg_depend d ON d.objid = c.oid AND d.deptype = 'i' " +
                "JOIN pg_class t ON t.oid = d.refobjid " +
                "WHERE c.relkind = 'S' AND t.relname = ANY (?) " +
                "AND (has_sequence_privilege(?, c.oid, 'USAGE') OR has_sequence_privilege(?, c.oid, 'SELECT') " +
                "     OR has_sequence_privilege(?, c.oid, 'UPDATE'))")) {
            st.setArray(1, db.createArrayOf("text", NEW_TABLES));
            st.setString(2, role);
            st.setString(3, role);
            st.setString(4, role);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    reachable.add(rs.getString(1));
                }
            }
        }
        assertEquals(List.of(), reachable, role + " keeps privileges on new sequences");
    }

    @Test
    void rowLevelSecurityIsEnabledOnTheNewTables() throws Exception {
        assertEquals(3, count(
            "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname = ANY (?) AND relrowsecurity",
            db.createArrayOf("text", NEW_TABLES)));
    }
}
