package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Notices commit or roll back with the cancellation that caused them, repeats add nothing,
 * claiming hands each due notice out once, and reviews record only what a person really did.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClosureNotificationDbTest extends AvailabilityDbTestBase {

    private static final String REVIEW = "SELECT public.mark_closure_review(?, ?, ?)::text";
    private static final String FOLLOW_UP = "SELECT public.queue_closure_follow_up(?, ?, ?)::text";

    private long notices() throws Exception {
        return count("SELECT count(*) FROM public.closure_notifications");
    }

    private long reviews() throws Exception {
        return count("SELECT count(*) FROM public.closure_reviews");
    }

    private long noticesOf(String kind) throws Exception {
        return count("SELECT count(*) FROM public.closure_notifications WHERE kind = ?", kind);
    }

    private long reviewId(int eventId) throws Exception {
        return count("SELECT id FROM public.closure_reviews WHERE event_id = ?", eventId);
    }

    private List<Map<String, Object>> claim() throws Exception {
        return claim(db, 50, 300);
    }

    private static List<Map<String, Object>> claim(Connection connection, int limit, int lease) throws Exception {
        return rows(connection, "SELECT * FROM public.claim_closure_notifications(?, ?)", limit, lease);
    }

    private static Map<String, Object> ofKind(List<Map<String, Object>> rows, String kind) {
        return rows.stream().filter(row -> kind.equals(row.get("kind"))).findFirst().orElseThrow();
    }

    private void checkIn(int eventId) throws Exception {
        exec("INSERT INTO public.attendees (event_id, sdccd_id, first_name, last_name, email) " +
            "VALUES (?, 1234567, 'Grace', 'Hopper', 'grace@student.sdccd.edu')", eventId);
    }

    private JsonNode review(int actor, long id, String note) throws Exception {
        return MAPPER.readTree((String) scalar(db, REVIEW, actor, id, note));
    }

    private JsonNode followUp(int actor, long id, String message) throws Exception {
        return MAPPER.readTree((String) scalar(db, FOLLOW_UP, actor, id, message));
    }

    /** One reservation cancelled by a confirmed closure of {@link #day}. */
    private int cancelled() throws Exception {
        int id = event(day, 9, 10);
        close(ids(id), day);
        return id;
    }

    // ---- Confirmed cancellation --------------------------------------------------------

    @Test
    void confirmedCancellationQueuesTheHostAndEveryEnabledAdmin() throws Exception {
        int first = event(day, 9, 10);
        int second = event(db, SECOND_ADMIN, day, 11, 12);
        checkIn(first);

        JsonNode saved = save(cutoff, dates(day), version(), ids(first, second), ADMIN, "  Water leak  ");

        assertFalse(saved.path("requires_confirmation").asBoolean());
        assertFalse(eventExists(first));
        assertFalse(eventExists(second));
        assertEquals(0, count("SELECT count(*) FROM public.attendees"));
        assertEquals(2, reviews());
        assertEquals(2, noticesOf("canceled"));
        // One summary for each enabled admin, not one per reservation.
        assertEquals(2, noticesOf("admin_review"));
        assertEquals(1, count("SELECT count(DISTINCT batch_id) FROM public.closure_reviews"));
        assertEquals(0, count("SELECT count(*) FROM public.closure_notifications WHERE recipient_user_id = ?", DISABLED_ADMIN));
    }

    @Test
    void theReviewKeepsWhatAnAdminNeedsAfterTheReservationIsGone() throws Exception {
        int id = event(day, 9, 10);
        checkIn(id);

        save(cutoff, dates(day), version(), ids(id), ADMIN, "  Water leak  ");

        Map<String, Object> row = rows(db, "SELECT * FROM public.closure_reviews WHERE event_id = ?", id).get(0);
        assertEquals("Lab", row.get("event_title"));
        assertEquals("Weekly lab", row.get("event_description"));
        assertEquals("Meeting", row.get("event_type"));
        assertEquals("Math", row.get("department"));
        assertEquals(true, row.get("is_public"));
        assertEquals(FACULTY, row.get("host_user_id"));
        assertEquals("Fay", row.get("host_first_name"));
        assertEquals("Faculty", row.get("host_last_name"));
        assertEquals("faculty@sdccd.edu", row.get("host_email"));
        assertEquals(1, row.get("check_in_count"));
        assertEquals(ADMIN, row.get("canceled_by"));
        assertEquals("Water leak", row.get("closure_reason"));
        assertEquals("unavailable_date", row.get("cause"));
        assertEquals("pending", row.get("review_status"));
        assertEquals(day.toString(), row.get("reservation_date").toString());
        assertEquals(at(day, 9, 0), ((java.sql.Timestamp) row.get("event_start_time")).toInstant());
    }

    @Test
    void checkInDetailsAreNotCopiedIntoTheReview() throws Exception {
        int id = event(day, 9, 10);
        checkIn(id);

        close(ids(id), day);

        String row = text("SELECT r::text FROM public.closure_reviews r");
        assertFalse(row.contains("grace@student"), row);
        assertFalse(row.contains("1234567"), row);
    }

    @Test
    void cancellationsFromMovingTheCutoffSaySo() throws Exception {
        int id = event(day, 9, 10);

        save(day.minusDays(1), "{}", version(), ids(id), ADMIN, null);

        assertEquals("after_last_bookable_date", text("SELECT cause FROM public.closure_reviews WHERE event_id = ?", id));
        assertNull(text("SELECT closure_reason FROM public.closure_reviews WHERE event_id = ?", id));
    }

    @Test
    void aSaveThatCancelsNothingQueuesNothing() throws Exception {
        close("{}", day);

        assertEquals(0, notices());
        assertEquals(0, reviews());
    }

    // ---- Refused or failed cancellation ------------------------------------------------

    @Test
    void unconfirmedSaveChangesNothingAndQueuesNothing() throws Exception {
        int id = event(day, 9, 10);
        String before = eventsSnapshot();

        JsonNode review = close("{}", day);

        assertTrue(review.path("requires_confirmation").asBoolean());
        assertEquals(id, review.path("reservations").path(0).path("id").asInt());
        assertEquals(before, eventsSnapshot());
        assertEquals(0, notices());
        assertEquals(0, reviews());
    }

    @Test
    void confirmingOnlySomeReservationsQueuesNothing() throws Exception {
        int first = event(day, 9, 10);
        event(day, 11, 12);

        JsonNode review = close(ids(first), day);

        assertTrue(review.path("requires_confirmation").asBoolean());
        assertTrue(eventExists(first));
        assertEquals(0, notices());
    }

    @Test
    void staleVersionQueuesNothing() throws Exception {
        int id = event(day, 9, 10);

        // 40001 would make PostgREST retry forever instead of answering.
        fails("BTV01", () -> save(cutoff, dates(day), version() + 1, ids(id), ADMIN, null));

        assertTrue(eventExists(id));
        assertEquals(0, notices());
    }

    @Test
    void nonAdminCannotSaveOrCancel() throws Exception {
        int id = event(day, 9, 10);

        fails("BTA01", () -> save(cutoff, dates(day), version(), ids(id), FACULTY, null));
        fails("BTA01", () -> save(cutoff, dates(day), version(), ids(id), DISABLED_ADMIN, null));

        assertTrue(eventExists(id));
        assertEquals(0, notices());
    }

    @Test
    void aRolledBackCancellationLeavesNoNotice() throws Exception {
        int id = event(day, 9, 10);
        long version = version();

        db.setAutoCommit(false);
        save(cutoff, dates(day), version, ids(id), ADMIN, null);
        db.rollback();
        db.setAutoCommit(true);

        assertTrue(eventExists(id));
        assertEquals(0, notices());
        assertEquals(0, reviews());
    }

    @Test
    void aFailureDuringTheDeleteLeavesNoNotice() throws Exception {
        int id = event(day, 9, 10);
        exec(superuser(), """
            CREATE FUNCTION public.test_refuse_delete() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION USING ERRCODE = 'XX001', MESSAGE = 'refused'; END $$;
            CREATE TRIGGER test_refuse_delete BEFORE DELETE ON public.events
              FOR EACH ROW EXECUTE FUNCTION public.test_refuse_delete();
            """);

        fails("XX001", () -> close(ids(id), day));

        assertTrue(eventExists(id));
        assertEquals(0, notices());
        assertEquals(0, reviews());
    }

    @Test
    void overlongClosureReasonIsRefused() throws Exception {
        int id = event(day, 9, 10);

        fails("22023", () -> save(cutoff, dates(day), version(), ids(id), ADMIN, "x".repeat(501)));

        assertTrue(eventExists(id));
    }

    // ---- Claiming ----------------------------------------------------------------------

    @Test
    void claimGivesEachNoticeItsRecipientAndDetails() throws Exception {
        int id = event(day, 9, 10);
        save(cutoff, dates(day), version(), ids(id), ADMIN, "Water leak");

        List<Map<String, Object>> claimed = claim();

        assertEquals(3, claimed.size());
        Map<String, Object> host = ofKind(claimed, "canceled");
        assertEquals("faculty@sdccd.edu", host.get("recipient_email"));
        assertEquals("Lab", host.get("event_title"));
        assertEquals("Water leak", host.get("closure_reason"));
        assertEquals("pending", host.get("review_status"));
        assertEquals(1, host.get("attempts"));
        assertNull(host.get("batch_reservations"));

        Map<String, Object> admin = ofKind(claimed, "admin_review");
        JsonNode batch = MAPPER.readTree(admin.get("batch_reservations").toString());
        assertEquals(1, batch.size());
        assertEquals("Lab", batch.path(0).path("title").asText());
        assertEquals("Fay", batch.path(0).path("host_first_name").asText());
        assertEquals("pending", batch.path(0).path("review_status").asText());
    }

    @Test
    void aClaimedNoticeIsNotHandedOutAgain() throws Exception {
        cancelled();

        assertEquals(3, claim().size());
        assertEquals(0, claim().size());
    }

    @Test
    void anExpiredLeaseIsClaimedAgain() throws Exception {
        cancelled();
        claim();
        exec(superuser(), "UPDATE public.closure_notifications SET locked_until = now() - interval '1 second'");

        List<Map<String, Object>> again = claim();

        assertEquals(3, again.size());
        assertEquals(2, again.get(0).get("attempts"));
    }

    @Test
    void concurrentClaimsNeverShareANotice() throws Exception {
        cancelled();
        Connection other = serviceRole();
        other.setAutoCommit(false);

        List<Map<String, Object>> first = claim(other, 1, 300);
        List<Map<String, Object>> second = claim();
        other.commit();

        assertEquals(1, first.size());
        assertEquals(2, second.size());
        assertTrue(second.stream().noneMatch(row -> row.get("id").equals(first.get(0).get("id"))));
    }

    @Test
    void aNoticeAnotherClaimHoldsIsLeftAloneRatherThanWaitedFor() throws Exception {
        cancelled();
        exec(superuser(), "UPDATE public.users SET enabled = false WHERE id = ?", SECOND_ADMIN);
        Connection other = serviceRole();
        other.setAutoCommit(false);
        rows(other, "SELECT id FROM public.closure_notifications WHERE recipient_user_id = ? FOR UPDATE", SECOND_ADMIN);
        exec("SET lock_timeout = '2s'");

        List<Map<String, Object>> claimed = claim();
        other.rollback();

        assertEquals(2, claimed.size());
        assertEquals("pending", text("SELECT status FROM public.closure_notifications WHERE recipient_user_id = ?", SECOND_ADMIN));
    }

    @Test
    void aNoticeWaitingForARetryIsNotClaimedEarly() throws Exception {
        cancelled();
        exec("UPDATE public.closure_notifications SET next_attempt_at = now() + interval '1 hour'");

        assertEquals(0, claim().size());
    }

    @Test
    void aNoticeForAReservationThatHasPassedIsSkipped() throws Exception {
        int id = cancelled();
        exec(superuser(), "UPDATE public.closure_reviews SET event_end_time = now() - interval '1 minute' WHERE event_id = ?", id);

        List<Map<String, Object>> claimed = claim();

        assertEquals(2, claimed.size());
        assertEquals("reservation_passed", text(
            "SELECT last_error FROM public.closure_notifications WHERE kind = 'canceled' AND status = 'skipped'"));
    }

    @Test
    void anAdminWhoLostAccessIsSkippedSoPrivateDetailsStayPrivate() throws Exception {
        cancelled();
        exec(superuser(), "UPDATE public.users SET enabled = false WHERE id = ?", SECOND_ADMIN);

        List<Map<String, Object>> claimed = claim();

        assertTrue(claimed.stream().noneMatch(row -> "second-admin@sdccd.edu".equals(row.get("recipient_email"))));
        assertEquals("recipient_not_admin", text(
            "SELECT last_error FROM public.closure_notifications WHERE recipient_user_id = ?", SECOND_ADMIN));
    }

    @Test
    void aHostAddressChangeIsUsedAtSendTime() throws Exception {
        cancelled();
        exec(superuser(), "UPDATE public.users SET email = 'new@sdccd.edu' WHERE id = ?", FACULTY);

        assertEquals("new@sdccd.edu", ofKind(claim(), "canceled").get("recipient_email"));
    }

    @Test
    void aDeletedHostStillGetsTheNoticeAtTheirLastAddress() throws Exception {
        cancelled();
        exec(superuser(), "DELETE FROM public.users WHERE id = ?", FACULTY);

        assertEquals("faculty@sdccd.edu", ofKind(claim(), "canceled").get("recipient_email"));
    }

    // ---- Delivery bookkeeping ----------------------------------------------------------

    @Test
    void theBackendCanRecordDeliveryButNothingElse() throws Exception {
        cancelled();

        exec("UPDATE public.closure_notifications SET status = 'sent', sent_via = 'test', sent_at = now()");

        fails("42501", () -> exec("UPDATE public.closure_notifications SET recipient_user_id = 1"));
        fails("42501", () -> exec("UPDATE public.closure_notifications SET follow_up_message = 'x'"));
        fails("42501", () -> exec("DELETE FROM public.closure_notifications"));
        fails("42501", () -> exec("INSERT INTO public.closure_notifications (kind, batch_id) " +
            "VALUES ('admin_review', gen_random_uuid())"));
    }

    @Test
    void theBackendCannotForgeOrEditAReview() throws Exception {
        cancelled();

        fails("42501", () -> exec("UPDATE public.closure_reviews SET review_status = 'reviewed', reviewed_by = 1, " +
            "reviewed_at = now()"));
        fails("42501", () -> exec("DELETE FROM public.closure_reviews"));
        fails("42501", () -> scalar(db, "SELECT public.queue_closure_cancellations('{1}', '{}', 1, null)::text"));
    }

    @Test
    void theSameNoticeCannotBeQueuedTwice() throws Exception {
        int id = cancelled();
        long review = reviewId(id);

        fails("23505", () -> exec(superuser(), "INSERT INTO public.closure_notifications (kind, review_id) " +
            "VALUES ('canceled', ?)", review));
        String batch = text("SELECT batch_id::text FROM public.closure_reviews WHERE id = ?", review);
        fails("23505", () -> exec(superuser(), "INSERT INTO public.closure_notifications (kind, batch_id, " +
            "recipient_user_id) VALUES ('admin_review', ?::uuid, ?)", batch, ADMIN));
    }

    // ---- Review ------------------------------------------------------------------------

    @Test
    void markingReviewedRecordsWhoWhenAndWhy() throws Exception {
        long id = reviewId(cancelled());

        JsonNode row = review(SECOND_ADMIN, id, "  Called the host  ");

        assertEquals("reviewed", row.path("review_status").asText());
        assertEquals(SECOND_ADMIN, row.path("reviewed_by").asInt());
        assertEquals("Called the host", row.path("review_note").asText());
        assertFalse(row.path("reviewed_at").isNull());
    }

    @Test
    void theFirstReviewStaysOnTheRecord() throws Exception {
        long id = reviewId(cancelled());
        review(ADMIN, id, "First");

        JsonNode repeat = review(SECOND_ADMIN, id, "Second");

        assertEquals(ADMIN, repeat.path("reviewed_by").asInt());
        assertEquals("First", repeat.path("review_note").asText());
    }

    @Test
    void reviewNeedsAnEnabledAdminAndAnExistingRow() throws Exception {
        long id = reviewId(cancelled());

        fails("BTA01", () -> review(FACULTY, id, null));
        fails("BTA01", () -> review(DISABLED_ADMIN, id, null));
        fails("BTN01", () -> review(ADMIN, 9999, null));
        assertEquals("pending", text("SELECT review_status FROM public.closure_reviews WHERE id = ?", id));
    }

    @Test
    void markingReviewedSendsNobodyAnything() throws Exception {
        long id = reviewId(cancelled());
        long before = notices();

        review(ADMIN, id, "Done");

        assertEquals(before, notices());
    }

    @Test
    void aReviewFinishedBeforeSendingIsReflectedAtSendTime() throws Exception {
        long id = reviewId(cancelled());
        review(ADMIN, id, null);

        List<Map<String, Object>> claimed = claim();

        assertEquals("reviewed", ofKind(claimed, "canceled").get("review_status"));
        JsonNode batch = MAPPER.readTree(ofKind(claimed, "admin_review").get("batch_reservations").toString());
        assertEquals("reviewed", batch.path(0).path("review_status").asText());
    }

    // ---- Follow-up ---------------------------------------------------------------------

    @Test
    void aFollowUpNeedsACompletedReview() throws Exception {
        long id = reviewId(cancelled());

        fails("BTN02", () -> followUp(ADMIN, id, "We found you a new room."));

        assertEquals(0, noticesOf("admin_follow_up"));
    }

    @Test
    void aFollowUpNeedsAnAdminAMessageAndAReview() throws Exception {
        long id = reviewId(cancelled());
        review(ADMIN, id, null);

        fails("BTA01", () -> followUp(FACULTY, id, "Hello"));
        fails("22023", () -> followUp(ADMIN, id, "   "));
        fails("22023", () -> followUp(ADMIN, id, "x".repeat(2001)));
        fails("BTN01", () -> followUp(ADMIN, 9999, "Hello"));
    }

    @Test
    void aFollowUpGoesToTheHostWithTheAdminsWords() throws Exception {
        long id = reviewId(cancelled());
        review(ADMIN, id, null);
        claim();

        JsonNode queued = followUp(SECOND_ADMIN, id, "We found you a new room.");

        assertEquals("admin_follow_up", queued.path("kind").asText());
        assertFalse(queued.has("follow_up_message"));
        Map<String, Object> row = claim().get(0);
        assertEquals("admin_follow_up", row.get("kind"));
        assertEquals("faculty@sdccd.edu", row.get("recipient_email"));
        assertEquals("We found you a new room.", row.get("follow_up_message"));
        assertEquals("reviewed", row.get("review_status"));
        assertEquals("Lab", row.get("event_title"));
    }

    @Test
    void theSameFollowUpIsOnlyQueuedOnce() throws Exception {
        long id = reviewId(cancelled());
        review(ADMIN, id, null);

        JsonNode first = followUp(ADMIN, id, "Same words");
        JsonNode repeat = followUp(ADMIN, id, "  Same words  ");
        JsonNode different = followUp(ADMIN, id, "Plans changed again");

        assertEquals(first.path("id").asLong(), repeat.path("id").asLong());
        assertNotEquals(first.path("id").asLong(), different.path("id").asLong());
        assertEquals(2, noticesOf("admin_follow_up"));
    }
}
