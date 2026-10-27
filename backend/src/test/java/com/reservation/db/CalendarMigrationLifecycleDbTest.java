package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Applying the migration to a database that already holds data, rolling it back, and
 * applying it again. Runs as the superuser that owns the schema (the migration role).
 */
@Testcontainers(disabledWithoutDocker = true)
class CalendarMigrationLifecycleDbTest extends CalendarDbTestBase {

    private static final String[] EXISTING_TABLES = {
        "user_roles", "users", "event_types", "events", "attendees", "sessions", "reservation_drafts"
    };

    @Override
    protected String template() {
        return CalendarDatabase.BASELINE_TEMPLATE;
    }

    private Connection owner;

    private void seedLegacyData() throws SQLException {
        owner = superuser();
        exec(owner, CalendarDatabase.SEED);
        exec(owner, """
            INSERT INTO public.events (host_user_id, start_time, end_time, event_type, description, title, department, is_public, recurrence_group_id)
            SELECT 2, ts, ts + interval '90 minutes', 'Meeting', 'legacy ' || n, 'Legacy ' || n, 'Math', n % 2 = 0,
                   CASE WHEN n % 3 = 0 THEN 'series-' || (n / 3) END
            FROM generate_series(1, 40) AS n,
                 LATERAL (SELECT (current_date - 200 + n * 9 || ' 16:00 America/Los_Angeles')::timestamptz AS ts) t;
            INSERT INTO public.attendees (event_id, sdccd_id, first_name, last_name, email)
            SELECT id, 5000000 + id, 'Stu', 'Dent', 's' || id || '@student.sdccd.edu' FROM public.events WHERE id % 4 = 0;
            INSERT INTO public.sessions (session_id, user_id, expires_at) VALUES ('legacy-session', 1, now() + interval '1 day');
            INSERT INTO public.reservation_drafts (user_id, host_user_id, source_event_id, draft_type, payload)
            VALUES (2, 2, 1, 'edit', '{"title":"Draft"}');
            """);
    }

    private Map<String, String> checksums() throws SQLException {
        Map<String, String> sums = new LinkedHashMap<>();
        for (String table : EXISTING_TABLES) {
            sums.put(table, (String) scalar(owner,
                "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM public." + table + " t"));
            sums.put(table + "#acl", (String) scalar(owner,
                "SELECT coalesce(relacl::text, '') || '/' || relrowsecurity FROM pg_class WHERE oid = ?::regclass",
                "public." + table));
            sums.put(table + "#triggers", (String) scalar(owner,
                "SELECT count(*)::text FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal",
                "public." + table));
        }
        sums.put("sequences", (String) scalar(owner,
            "SELECT string_agg(sequencename || '=' || coalesce(last_value::text, 'null'), ',' ORDER BY sequencename) " +
                "FROM pg_sequences WHERE schemaname = 'public' AND sequencename NOT IN " +
                "('semesters_id_seq', 'unavailable_days_id_seq', 'calendar_override_audit_id_seq')"));
        return sums;
    }

    private Map<String, String> withoutTriggerCounts(Map<String, String> sums) {
        Map<String, String> copy = new LinkedHashMap<>(sums);
        copy.keySet().removeIf(key -> key.endsWith("#triggers"));
        return copy;
    }

    private void migrate() throws SQLException {
        exec(owner, CalendarDatabase.read(CalendarDatabase.MIGRATION));
    }

    private void rollback() throws SQLException {
        exec(owner, CalendarDatabase.read(CalendarDatabase.ROLLBACK));
    }

    @Test
    void migrationLeavesExistingRowsAndPrivilegesUnchanged() throws Exception {
        seedLegacyData();
        Map<String, String> before = checksums();

        migrate();

        Map<String, String> after = checksums();
        assertEquals(withoutTriggerCounts(before), withoutTriggerCounts(after));
        // The only change to existing tables is the three calendar triggers on events.
        assertEquals("0", before.get("events#triggers"));
        assertEquals("3", after.get("events#triggers"));
        for (String table : EXISTING_TABLES) {
            if (!table.equals("events")) {
                assertEquals(before.get(table + "#triggers"), after.get(table + "#triggers"), table);
            }
        }
    }

    @Test
    void legacyReservationsStayEditableButCannotMoveWithoutASemester() throws Exception {
        seedLegacyData();
        migrate();
        int legacy = (int) scalarLong(owner, "SELECT max(id) FROM public.events");

        exec(db, "UPDATE public.events SET title = 'Renamed after migration' WHERE id = ?", legacy);
        fails("BTC01", () -> exec(db,
            "UPDATE public.events SET start_time = start_time + interval '7 days', end_time = end_time + interval '7 days' WHERE id = ?",
            legacy));
        fails("BTC01", () -> event(today.plusDays(3), 9, 10));
    }

    @Test
    void rollbackRemovesEverythingAndKeepsReservationsAndReapplyWorks() throws Exception {
        seedLegacyData();
        Map<String, String> beforeMigration = checksums();
        migrate();

        // Use the feature so the rollback has something to remove.
        currentSemester();
        closure(today.plusDays(10));
        int added = event(today.plusDays(9), 9, 10);
        scalar(db, "SELECT count(*) FROM public.create_events_with_calendar_override(1, 'x', ?::jsonb)",
            eventsJson(new java.time.Instant[][] {{at(today.plusDays(10), 9, 0), at(today.plusDays(10), 10, 0)}}));
        String eventsAfterUse = text("SELECT md5(string_agg(e::text, '|' ORDER BY e.id)) FROM public.events e");

        rollback();

        assertEquals(eventsAfterUse,
            scalar(owner, "SELECT md5(string_agg(e::text, '|' ORDER BY e.id)) FROM public.events e"),
            "rollback must not touch reservations");
        for (String table : new String[] {"semesters", "unavailable_days", "calendar_override_audit"}) {
            assertNull(scalar(owner, "SELECT to_regclass(?)::text", "public." + table), table + " survived rollback");
        }
        assertEquals(0, scalarLong(owner,
            "SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND (proname LIKE 'calendar\\_%' " +
                "OR proname IN ('events_enforce_calendar_rules', 'unavailable_days_guard', 'semesters_guard', " +
                "'create_events_with_calendar_override', 'update_event_with_calendar_override', " +
                "'create_unavailable_day_with_override', 'update_unavailable_day_with_override'))"));
        assertEquals(0, scalarLong(owner,
            "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.events'::regclass AND NOT tgisinternal"));
        Map<String, String> afterRollback = checksums();
        assertEquals(beforeMigration.get("events#acl"), afterRollback.get("events#acl"));
        assertEquals(beforeMigration.get("events#triggers"), afterRollback.get("events#triggers"));

        // Previous behaviour is back: no semester needed.
        event(today.plusDays(400), 9, 10);
        assertTrue(added > 0);

        // Re-applying works on the rolled-back database and enforces the rules again.
        migrate();
        assertEquals(0, count("SELECT count(*) FROM public.semesters"));
        fails("BTC01", () -> event(today.plusDays(5), 9, 10));
        currentSemester();
        event(today.plusDays(5), 9, 10);
    }

    @Test
    void reapplyingOnAMigratedDatabaseFailsWithoutPartialChanges() throws Exception {
        seedLegacyData();
        migrate();
        currentSemester();
        closure(today.plusDays(10));
        Map<String, String> before = checksums();
        String semesters = text("SELECT string_agg(s::text, '|') FROM public.semesters s");

        PSQLException error = assertThrows(PSQLException.class, this::migrate);
        assertEquals("42P07", error.getSQLState(), error.getMessage());
        // The script's own BEGIN was aborted; end it so the connection is usable again.
        exec(owner, "ROLLBACK");

        assertEquals(before, checksums());
        assertEquals(semesters, text("SELECT string_agg(s::text, '|') FROM public.semesters s"));
        assertEquals(1, count("SELECT count(*) FROM public.unavailable_days"));
    }

    @Test
    void rollbackIsIdempotent() throws Exception {
        seedLegacyData();
        migrate();
        rollback();
        rollback();
        assertNull(scalar(owner, "SELECT to_regclass('public.semesters')::text"));
    }
}
