package com.reservation.notifications;

import com.reservation.config.SupabaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ClosureNotificationDispatcherTest {

    private static final String CLAIM = "rpc/claim_closure_notifications";
    private static final String CONTACT = "Call the front desk.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stands in for the provider, so these tests cannot reach a mail server. */
    private static class RecordingSender implements EmailSender {
        final List<EmailMessage> sent = new ArrayList<>();
        RuntimeException failure;
        String failOnlyFor;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void send(EmailMessage message) {
            if (failure != null && (failOnlyFor == null || failOnlyFor.equals(message.to()))) {
                throw failure;
            }
            sent.add(message);
        }
    }

    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final RecordingSender sender = new RecordingSender();
    private final ClosureNotificationDispatcher dispatcher = new ClosureNotificationDispatcher(supabase, sender, CONTACT);

    @BeforeEach
    void updatesSucceed() {
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));
    }

    private ObjectNode notification(long id, String kind, String email, int attempts) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("id", id);
        row.put("kind", kind);
        row.put("attempts", attempts);
        row.put("cause", "unavailable_date");
        row.put("closure_reason", "Water leak");
        row.put("review_status", "pending");
        row.put("event_title", "Robotics Club");
        row.put("event_start_time", "2026-11-20T22:00:00+00:00");
        row.put("event_end_time", "2026-11-20T23:30:00+00:00");
        if (email == null) {
            row.putNull("recipient_email");
        } else {
            row.put("recipient_email", email);
        }
        row.put("recipient_first_name", "Ada");
        row.put("recipient_last_name", "Lovelace");
        return row;
    }

    private void claims(JsonNode... rows) {
        ArrayNode claimed = MAPPER.createArrayNode();
        for (JsonNode row : rows) {
            claimed.add(row);
        }
        when(supabase.post(eq(CLAIM), anyString())).thenReturn(claimed.toString());
    }

    private JsonNode outcome(long id) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(supabase).patchResponse(eq("closure_notifications?id=eq." + id + "&status=eq.sending"), body.capture());
        return MAPPER.readTree(body.getValue());
    }

    @Test
    void canceledNoticeIsSentAndMarkedSent() {
        claims(notification(7, "canceled", "ada@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        assertEquals(1, sender.sent.size());
        assertEquals(ClosureEmailTemplates.CANCELED_SUBJECT, sender.sent.get(0).subject());
        assertEquals("ada@sdmesa.edu", sender.sent.get(0).to());
        JsonNode outcome = outcome(7);
        assertEquals("sent", outcome.path("status").asText());
        assertEquals("test", outcome.path("sent_via").asText());
        assertFalse(outcome.path("sent_at").asText().isEmpty());
        assertTrue(outcome.path("locked_until").isNull());
    }

    @Test
    void adminReviewListsTheBatchAndNeverUsesTheCancellationNotice() {
        ObjectNode row = notification(7, "admin_review", "admin@sdmesa.edu", 1);
        row.set("batch_reservations", MAPPER.readTree("""
            [{"review_id":3,"title":"Robotics Club","start_time":"2026-11-20T22:00:00+00:00",
              "end_time":"2026-11-20T23:30:00+00:00","host_first_name":"Grace","host_last_name":"Hopper",
              "cause":"unavailable_date","closure_reason":"Water leak","review_status":"pending"}]
            """));
        claims(row);

        dispatcher.dispatchDue();

        assertEquals(ClosureEmailTemplates.ADMIN_REVIEW_SUBJECT, sender.sent.get(0).subject());
        assertTrue(sender.sent.get(0).body().contains("Grace Hopper"));
        assertEquals("sent", outcome(7).path("status").asText());
    }

    @Test
    void followUpCarriesTheAdminsMessage() {
        ObjectNode row = notification(7, "admin_follow_up", "ada@sdmesa.edu", 1);
        row.put("review_status", "reviewed");
        row.put("follow_up_message", "We found you a new room.");
        claims(row);

        dispatcher.dispatchDue();

        assertEquals(ClosureEmailTemplates.FOLLOW_UP_SUBJECT, sender.sent.get(0).subject());
        assertTrue(sender.sent.get(0).body().contains("We found you a new room."));
    }

    @Test
    void theConfiguredContactInstructionsAppearInTheEmail() {
        claims(notification(7, "canceled", "ada@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        assertTrue(sender.sent.get(0).body().contains(CONTACT));
    }

    @Test
    void aMissingReviewStatusNeverClaimsAReviewHappened() {
        ObjectNode row = notification(7, "canceled", "ada@sdmesa.edu", 1);
        row.putNull("review_status");
        claims(row);

        dispatcher.dispatchDue();

        assertFalse(sender.sent.get(0).body().contains("has reviewed"));
    }

    @Test
    void aNullReasonIsLeftOutOfTheEmail() {
        ObjectNode row = notification(7, "canceled", "ada@sdmesa.edu", 1);
        row.putNull("closure_reason");
        claims(row);

        dispatcher.dispatchDue();

        assertFalse(sender.sent.get(0).body().contains("Reason given"));
        assertFalse(sender.sent.get(0).body().contains("null"));
    }

    @Test
    void anUnknownKindIsNeverGuessedAt() {
        claims(notification(7, "preserved", "ada@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        assertTrue(sender.sent.isEmpty());
        JsonNode outcome = outcome(7);
        assertEquals("failed", outcome.path("status").asText());
        assertEquals("unknown_kind", outcome.path("last_error").asText());
    }

    @Test
    void multipleNoticesGoSeparatelyToTheirOwnHosts() {
        claims(
            notification(1, "canceled", "ada@sdmesa.edu", 1),
            notification(2, "canceled", "grace@sdmesa.edu", 1),
            notification(3, "canceled", "katherine@sdmesa.edu", 1)
        );

        dispatcher.dispatchDue();

        assertEquals(
            List.of("ada@sdmesa.edu", "grace@sdmesa.edu", "katherine@sdmesa.edu"),
            sender.sent.stream().map(EmailMessage::to).toList()
        );
        sender.sent.forEach(message -> assertFalse(message.to().contains(",")));
        assertEquals("sent", outcome(1).path("status").asText());
        assertEquals("sent", outcome(2).path("status").asText());
        assertEquals("sent", outcome(3).path("status").asText());
    }

    @Test
    void disabledProviderClaimsNothing() {
        ClosureNotificationDispatcher disabled =
            new ClosureNotificationDispatcher(supabase, new DisabledEmailSender(), CONTACT);

        disabled.dispatchDue();

        verifyNoInteractions(supabase);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,5", "3,30", "4,120"})
    void temporaryFailureIsRetriedLater(int attempts, int expectedMinutes) {
        sender.failure = new EmailDeliveryException(false);
        claims(notification(7, "canceled", "ada@sdmesa.edu", attempts));
        Instant before = Instant.now();

        dispatcher.dispatchDue();

        JsonNode outcome = outcome(7);
        assertEquals("pending", outcome.path("status").asText());
        assertEquals("send_failed", outcome.path("last_error").asText());
        Duration delay = Duration.between(before, Instant.parse(outcome.path("next_attempt_at").asText()));
        assertTrue(delay.toMinutes() >= expectedMinutes - 1 && delay.toMinutes() <= expectedMinutes, delay.toString());
    }

    @Test
    void lastAttemptGivesUpAsFailed() {
        sender.failure = new EmailDeliveryException(false);
        claims(notification(7, "canceled", "ada@sdmesa.edu", 5));

        dispatcher.dispatchDue();

        JsonNode outcome = outcome(7);
        assertEquals("failed", outcome.path("status").asText());
        assertEquals("send_failed", outcome.path("last_error").asText());
    }

    @Test
    void rejectedAddressFailsWithoutRetrying() {
        sender.failure = new EmailDeliveryException(true);
        claims(notification(7, "canceled", "ada@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        JsonNode outcome = outcome(7);
        assertEquals("failed", outcome.path("status").asText());
        assertEquals("recipient_rejected", outcome.path("last_error").asText());
    }

    @Test
    void unexpectedSenderErrorIsRetried() {
        sender.failure = new IllegalStateException("boom");
        claims(notification(7, "canceled", "ada@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        assertEquals("pending", outcome(7).path("status").asText());
    }

    @Test
    void oneFailureDoesNotStopTheOtherNotices() {
        sender.failure = new EmailDeliveryException(false);
        sender.failOnlyFor = "ada@sdmesa.edu";
        claims(notification(1, "canceled", "ada@sdmesa.edu", 1), notification(2, "canceled", "grace@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        assertEquals("pending", outcome(1).path("status").asText());
        assertEquals("sent", outcome(2).path("status").asText());
    }

    @Test
    void missingAddressIsRecordedAndNothingIsSent() {
        claims(notification(7, "canceled", null, 1));

        dispatcher.dispatchDue();

        assertTrue(sender.sent.isEmpty());
        JsonNode outcome = outcome(7);
        assertEquals("failed", outcome.path("status").asText());
        assertEquals("missing_recipient", outcome.path("last_error").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-an-address", "a@b.edu, c@d.edu", "Ada <ada@sdmesa.edu>", "ada@sdmesa.edu\r\nBcc: x@example.com"
    })
    void invalidAddressIsRecordedAndNothingIsSent(String address) {
        claims(notification(7, "canceled", address, 1));

        dispatcher.dispatchDue();

        assertTrue(sender.sent.isEmpty());
        assertEquals("invalid_recipient", outcome(7).path("last_error").asText());
    }

    @Test
    void failedStatusUpdateDoesNotStopTheRun() {
        when(supabase.patchResponse(contains("id=eq.1&"), anyString())).thenThrow(new RuntimeException("down"));
        claims(notification(1, "canceled", "ada@sdmesa.edu", 1), notification(2, "canceled", "grace@sdmesa.edu", 1));

        dispatcher.dispatchDue();

        assertEquals(2, sender.sent.size());
        assertEquals("sent", outcome(2).path("status").asText());
    }

    @Test
    void databaseOutageDuringClaimSendsNothingAndDoesNotThrow() {
        when(supabase.post(eq(CLAIM), anyString())).thenThrow(new RuntimeException("down"));

        assertDoesNotThrow(dispatcher::dispatchDue);

        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void logsCarryNoAddressNameOrProviderText(CapturedOutput output) {
        sender.failure = new IllegalStateException("550 ada@sdmesa.edu rejected");
        claims(notification(7, "canceled", "ada@sdmesa.edu", 1));
        when(supabase.patchResponse(anyString(), anyString())).thenThrow(new RuntimeException("ada@sdmesa.edu"));

        dispatcher.dispatchDue();

        assertTrue(output.getAll().contains("Closure notification 7"), output.getAll());
        assertFalse(output.getAll().contains("ada@sdmesa.edu"), output.getAll());
        assertFalse(output.getAll().contains("Lovelace"), output.getAll());
        assertFalse(output.getAll().contains("Robotics Club"), output.getAll());
    }
}
