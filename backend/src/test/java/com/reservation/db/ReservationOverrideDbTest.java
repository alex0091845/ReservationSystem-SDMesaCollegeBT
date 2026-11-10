package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An admin can book a closed date, nobody else can, and the date stays closed. */
@Testcontainers(disabledWithoutDocker = true)
class ReservationOverrideDbTest extends AvailabilityDbTestBase {

    private static final String DIRECT_INSERT =
        "INSERT INTO public.events (host_user_id, start_time, end_time, event_type, description, title, department, " +
            "is_public) VALUES (2, ?::timestamptz, ?::timestamptz, 'Meeting', 'd', 't', 'Math', true)";

    private long audits() throws Exception {
        return count("SELECT count(*) FROM public.reservation_overrides");
    }

    // ---- Who can override ----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {FACULTY, DISABLED_ADMIN, 9999})
    void onlyAnEnabledAdminCanOverride(int actor) throws Exception {
        close("{}", day);
        String before = eventsSnapshot();

        fails("BTA01", () -> special(actor, "Please", eventJson(day, 9, 10)));

        assertEquals(before, eventsSnapshot());
        assertEquals(0, audits());
    }

    @Test
    void adminCreatesAReservationOnAClosedDateAndTheOverrideIsAudited() throws Exception {
        close("{}", day);

        JsonNode result = special(ADMIN, "  Accreditation visit  ", eventJson(day, 9, 10));

        int id = result.path("reservation").path("id").asInt();
        assertTrue(eventExists(id));
        assertFalse(result.path("reservation").path("is_closure").asBoolean());
        Map<String, Object> audit = rows(db, "SELECT * FROM public.reservation_overrides").get(0);
        assertEquals(result.path("override_id").asLong(), ((Number) audit.get("id")).longValue());
        assertEquals(id, audit.get("event_id"));
        assertEquals(day.toString(), audit.get("override_date").toString());
        assertEquals("Accreditation visit", audit.get("reason"));
        assertEquals(ADMIN, audit.get("actor_user_id"));
        assertEquals("admin@sdccd.edu", audit.get("actor_email"));
        assertEquals(FACULTY, audit.get("host_user_id"));
        assertNotNull(audit.get("created_at"));
    }

    @Test
    void theAuditRecordOutlivesTheReservation() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);

        exec("DELETE FROM public.events WHERE id = ?", id);

        assertEquals(1, count("SELECT count(*) FROM public.reservation_overrides WHERE event_id = ?", id));
    }

    // ---- Validation ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aReasonIsRequired(String reason) throws Exception {
        close("{}", day);

        fails("22023", () -> special(ADMIN, reason, eventJson(day, 9, 10)));
        fails("22023", () -> special(ADMIN, null, eventJson(day, 9, 10)));

        assertEquals(0, audits());
    }

    @Test
    void anOverlongReasonIsRefused() throws Exception {
        close("{}", day);

        fails("22023", () -> special(ADMIN, "x".repeat(501), eventJson(day, 9, 10)));
    }

    @Test
    void anOpenDateNeedsNoOverride() throws Exception {
        fails("BTS01", () -> special(ADMIN, "Not needed", eventJson(day, 9, 10)));

        assertEquals(0, audits());
    }

    @Test
    void anOverrideDoesNotReachPastTheCutoff() throws Exception {
        fails("BTS02", () -> special(ADMIN, "Too late", eventJson(cutoff.plusDays(1), 9, 10)));
    }

    @Test
    void pastMultiDayLateAndClosureShapedRequestsAreRefused() throws Exception {
        close("{}", day);

        fails("22023", () -> special(ADMIN, "r", eventJson(FACULTY, Instant.now().minusSeconds(60), Instant.now())));
        fails("22023", () -> special(ADMIN, "r", eventJson(FACULTY, at(day, 9, 0), at(day.plusDays(1), 10, 0))));
        fails("22023", () -> special(ADMIN, "r", eventJson(day, 16, 18)));
        fails("22023", () -> special(ADMIN, "r", eventJson(day, 10, 9)));
        fails("22023", () -> special(ADMIN, "r",
            eventJson(day, 9, 10).replace("\"Meeting\"", "\"Unavailable\"")));

        assertEquals(0, audits());
    }

    @Test
    void overlappingSpecialReservationsAreRefusedAndLeaveNoAudit() throws Exception {
        close("{}", day);
        special(day, 9, 11);

        fails("23P01", () -> special(ADMIN, "Second", eventJson(day, 10, 12)));

        assertEquals(1, audits());
    }

    @Test
    void adjacentSpecialReservationsAreAllowed() throws Exception {
        close("{}", day);
        special(day, 9, 10);

        assertTrue(eventExists(special(day, 10, 11)));
    }

    @Test
    void anUnknownHostLeavesNoAudit() throws Exception {
        close("{}", day);

        fails("23503", () -> special(ADMIN, "r", eventJson(9999, at(day, 9, 0), at(day, 10, 0))));

        assertEquals(0, audits());
    }

    // ---- The date stays closed -----------------------------------------------------

    @Test
    void theDateStaysUnavailableForEveryoneElse() throws Exception {
        close("{}", day);
        special(day, 9, 10);

        JsonNode settings = MAPPER.readTree(text("SELECT public.get_reservation_availability()::text"));
        assertEquals(day.toString(), settings.path("invalid_dates").path(0).asText());
        fails("23514", () -> exec(DIRECT_INSERT, at(day, 11, 0), at(day, 12, 0)));
    }

    @Test
    void aReservationCannotBeMovedOntoAClosedDate() throws Exception {
        int open = event(day.plusDays(1), 9, 10);
        close("{}", day);

        fails("23514", () -> exec("UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz " +
            "WHERE id = ?", at(day, 9, 0), at(day, 10, 0), open));
    }

    @Test
    void aSpecialReservationCannotChangeTimeWhileItsDateIsClosed() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);

        fails("23514", () -> exec("UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz " +
            "WHERE id = ?", at(day, 10, 0), at(day, 11, 0), id));
    }

    @Test
    void aSpecialReservationMovedOffCannotComeBackWhileTheDateIsClosed() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);
        String move = "UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?";
        exec(move, at(day.plusDays(1), 9, 0), at(day.plusDays(1), 10, 0), id);

        fails("23514", () -> exec(move, at(day, 9, 0), at(day, 10, 0), id));
    }

    @Test
    void aSpecialReservationCanStillBeRenamedAndCheckedInto() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);

        exec("UPDATE public.events SET title = 'Renamed' WHERE id = ?", id);
        exec("INSERT INTO public.attendees (event_id, first_name) VALUES (?, 'Grace')", id);

        assertEquals("Renamed", text("SELECT title FROM public.events WHERE id = ?", id));
    }

    // ---- The database refuses to be talked around ------------------------------------

    @Test
    void theBackendRoleCannotForgeAnAuditRow() throws Exception {
        close("{}", day);

        fails("42501", () -> exec("INSERT INTO public.reservation_overrides (event_id, override_date, reason, " +
            "actor_user_id, actor_email, host_user_id, event_start_time, event_end_time) " +
            "VALUES (500, ?::date, 'x', 1, 'a', 2, now(), now())", day));
    }

    @Test
    void theBackendRoleCannotRewriteOrRemoveAnAuditRow() throws Exception {
        close("{}", day);
        special(day, 9, 10);

        fails("42501", () -> exec("UPDATE public.reservation_overrides SET reason = 'changed'"));
        fails("42501", () -> exec("DELETE FROM public.reservation_overrides"));
    }

    @Test
    void browserRolesCannotReachAnyOfIt() throws Exception {
        close("{}", day);
        for (String role : new String[] {"anon", "authenticated"}) {
            Connection connection = as(role);
            fails("42501", () -> scalar(connection, "SELECT count(*) FROM public.reservation_overrides"));
            fails("42501", () -> scalar(connection, "SELECT count(*) FROM public.closure_reviews"));
            fails("42501", () -> scalar(connection, "SELECT count(*) FROM public.closure_notifications"));
            fails("42501", () -> scalar(connection, "SELECT public.create_special_reservation(1, 'r', '{}'::jsonb)"));
            fails("42501", () -> scalar(connection, "SELECT public.claim_closure_notifications(1, 1)"));
        }
    }

    // ---- Later availability saves -----------------------------------------------------

    @Test
    void savingAgainKeepsASpecialReservationWithoutAskingOrNotifying() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);

        JsonNode saved = close("{}", day);

        assertFalse(saved.path("requires_confirmation").asBoolean());
        assertTrue(eventExists(id));
        assertEquals(0, count("SELECT count(*) FROM public.closure_notifications"));
    }

    @Test
    void reopeningThenClosingAgainAsksAboutTheSpecialReservation() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);
        close("{}");

        JsonNode review = close("{}", day);

        assertTrue(review.path("requires_confirmation").asBoolean());
        assertEquals(id, review.path("reservations").path(0).path("id").asInt());
        assertTrue(eventExists(id));
    }

    @Test
    void movingTheCutoffBeforeASpecialReservationAsksAboutIt() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);

        JsonNode review = save(day.minusDays(1), "{}", version(), "{}", ADMIN, null);

        assertTrue(review.path("requires_confirmation").asBoolean());
        save(day.minusDays(1), "{}", version(), ids(id), ADMIN, null);
        assertFalse(eventExists(id));
        assertEquals("after_last_bookable_date", text("SELECT cause FROM public.closure_reviews WHERE event_id = ?", id));
    }

    @Test
    void aSpecialReservationMovedOffItsDateLosesTheProtection() throws Exception {
        close("{}", day);
        int id = special(day, 9, 10);
        exec("UPDATE public.events SET start_time = ?::timestamptz, end_time = ?::timestamptz WHERE id = ?",
            at(day.plusDays(1), 9, 0), at(day.plusDays(1), 10, 0), id);

        JsonNode review = close("{}", day, day.plusDays(1));

        assertTrue(review.path("requires_confirmation").asBoolean());
    }
}
