package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Rerunning and rolling back admin_override_notifications.sql and reservation_availability.sql. */
@Testcontainers(disabledWithoutDocker = true)
class AdminOverrideMigrationDbTest extends AvailabilityDbTestBase {

    // The last main commit before this feature; the rollback reruns its reservation_availability.sql
    private static final String PRE_FEATURE_COMMIT = "811210c";

    private void apply(Connection owner) throws Exception {
        exec(owner, AvailabilityDatabase.read(AvailabilityDatabase.AVAILABILITY));
        exec(owner, AvailabilityDatabase.read(AvailabilityDatabase.NOTIFICATIONS));
    }

    private void rollBack(Connection owner) throws Exception {
        exec(owner, AvailabilityDatabase.read(AvailabilityDatabase.NOTIFICATIONS_ROLLBACK));
    }

    /** reservation_availability.sql as it was before this feature, or null when git cannot show it. */
    private static String preFeatureAvailabilityScript() {
        try {
            Process git = new ProcessBuilder("git", "show",
                PRE_FEATURE_COMMIT + ":backend/sql/reservation_availability.sql")
                .directory(AvailabilityDatabase.BACKEND_DIR.toFile()).redirectErrorStream(false).start();
            String script = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0 ? script : null;
        } catch (IOException | InterruptedException error) {
            return null;
        }
    }

    @Test
    void rerunningBothScriptsIsSafeAndKeepsTheData() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);
        int cancelled = event(day.plusDays(1), 9, 10);
        close(ids(cancelled), day, day.plusDays(1));

        apply(superuser());

        assertTrue(eventExists(id));
        assertEquals(1, count("SELECT count(*) FROM public.reservation_overrides"));
        assertEquals(1, count("SELECT count(*) FROM public.closure_reviews"));
        assertTrue(count("SELECT count(*) FROM public.closure_notifications") > 0);
    }

    @Test
    void rerunningReservationAvailabilityAloneKeepsTheFeature() throws Exception {
        exec(superuser(), AvailabilityDatabase.read(AvailabilityDatabase.AVAILABILITY));
        close("{}", day);

        int id = special(day, 9, 10);
        int cancelled = event(day.plusDays(1), 9, 10);
        close(ids(cancelled), day, day.plusDays(1));

        assertTrue(eventExists(id));
        assertEquals(1, count("SELECT count(*) FROM public.closure_reviews WHERE event_id = ?", cancelled));
    }

    @Test
    void theOldFourArgumentSaveIsGoneOnceApplied() throws Exception {
        Connection owner = superuser();
        exec(owner, "CREATE FUNCTION public.save_reservation_availability(date, date[], bigint, integer[]) " +
            "RETURNS jsonb LANGUAGE sql AS 'SELECT NULL::jsonb'");

        apply(owner);

        assertEquals(1, count("SELECT count(*) FROM pg_proc WHERE proname = 'save_reservation_availability'"));
    }

    @Test
    void rollbackRemovesTheNewObjectsAndLeavesReservationsAlone() throws Exception {
        Connection owner = superuser();
        int kept = event(day.plusDays(2), 9, 10);
        int cancelled = event(day, 9, 10);
        close(ids(cancelled), day);

        rollBack(owner);

        assertTrue(eventExists(kept));
        for (String table : new String[] {"reservation_overrides", "closure_reviews", "closure_notifications"}) {
            assertNull(scalar(owner, "SELECT to_regclass(?)", "public." + table));
        }
        assertEquals(0, count("SELECT count(*) FROM pg_proc WHERE proname IN ('create_special_reservation', " +
            "'queue_closure_cancellations', 'claim_closure_notifications', 'mark_closure_review', " +
            "'queue_closure_follow_up', 'require_enabled_admin', 'save_reservation_availability')"));
    }

    @Test
    void untilTheOriginalScriptIsRerunClosedDatesStayRefused() throws Exception {
        close("{}", day);
        rollBack(superuser());

        assertNotNull(fails("42P01", () -> event(day, 9, 10)));
        assertTrue(eventExists(event(day.plusDays(1), 9, 10)));
    }

    @Test
    void rollbackThenThePreFeatureScriptRestoresTheOriginalBehaviour() throws Exception {
        String original = preFeatureAvailabilityScript();
        assumeTrue(original != null, "git history with " + PRE_FEATURE_COMMIT + " is needed for this test");
        Connection owner = superuser();
        int id = event(day, 9, 10);

        rollBack(owner);
        exec(owner, original);

        String saved = (String) scalar(db,
            "SELECT public.save_reservation_availability(?::date, ?::date[], ?, ?::integer[])::text",
            cutoff, dates(day), version(), ids(id));
        assertTrue(saved.contains(day.toString()));
        assertFalse(eventExists(id));
        assertTrue(fails("23514", () -> event(day, 9, 10)).getMessage()
            .contains("Reservation falls on an unavailable date."));
    }

    @Test
    void reapplyingAfterRollbackRestoresTheFeature() throws Exception {
        Connection owner = superuser();
        rollBack(owner);

        apply(owner);
        close("{}", day);

        assertTrue(eventExists(special(day, 9, 10)));
    }
}
