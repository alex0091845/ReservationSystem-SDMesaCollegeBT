package com.reservation.notifications;

import com.reservation.config.SupabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sends the notices the database queued. The cancellation has already committed by
 * the time a row exists, so nothing here can affect a reservation: a failed send only
 * changes the notification's own row.
 *
 * <p>Logs carry the notification id and an error type, never addresses, names,
 * message text, or provider responses.
 */
@Service
public class ClosureNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClosureNotificationDispatcher.class);

    // Ten sends at the 20-second worst case (connect plus read) finish inside the lease.
    private static final int BATCH_SIZE = 10;
    private static final int LEASE_SECONDS = 300;
    private static final Set<String> KINDS = Set.of("canceled", "admin_review", "admin_follow_up");
    // The wait after each failed attempt. One more failure after these gives up.
    private static final List<Duration> RETRY_DELAYS = List.of(
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)
    );

    private final SupabaseClient supabase;
    private final EmailSender emailSender;
    private final String contactInstructions;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClosureNotificationDispatcher(
        SupabaseClient supabase,
        EmailSender emailSender,
        @Value("${notifications.contact-instructions}") String contactInstructions
    ) {
        this.supabase = supabase;
        this.emailSender = emailSender;
        this.contactInstructions = contactInstructions;
    }

    @Scheduled(
        initialDelayString = "${notifications.poll-seconds}",
        fixedDelayString = "${notifications.poll-seconds}",
        timeUnit = TimeUnit.SECONDS
    )
    public void dispatchDue() {
        if (!emailSender.enabled()) {
            return;
        }

        try {
            for (JsonNode notification : claimDue()) {
                deliver(notification);
            }
        } catch (RuntimeException error) {
            log.warn("Closure notification run failed: {}", error.getClass().getSimpleName());
        }
    }

    private JsonNode claimDue() {
        ObjectNode call = mapper.createObjectNode();
        call.put("p_limit", BATCH_SIZE);
        call.put("p_lease_seconds", LEASE_SECONDS);

        return mapper.readTree(supabase.post("rpc/claim_closure_notifications", call.toString()));
    }

    private void deliver(JsonNode notification) {
        long id = notification.path("id").asLong();
        ObjectNode outcome = attempt(notification);

        try {
            SupabaseClient.SupabaseResponse saved = supabase.patchResponse(
                "closure_notifications?id=eq." + id + "&status=eq.sending", outcome.toString()
            );

            if (!saved.isSuccessful()) {
                log.warn("Closure notification {} could not be updated.", id);
            }
        } catch (RuntimeException error) {
            // The lease runs out and the row is claimed again.
            log.warn("Closure notification {} could not be updated: {}", id, error.getClass().getSimpleName());
        }
    }

    private ObjectNode attempt(JsonNode notification) {
        String recipient = notification.path("recipient_email").asText("").trim();

        // Waits for an admin to fix the address and retry, since sending again cannot help.
        if (recipient.isEmpty()) {
            return failed("missing_recipient");
        }

        if (!EmailSenderConfig.validAddress(recipient)) {
            return failed("invalid_recipient");
        }

        String kind = notification.path("kind").asText();

        // Anything else has no template, and guessing could tell someone the wrong thing.
        if (!KINDS.contains(kind)) {
            return failed("unknown_kind");
        }

        try {
            emailSender.send(message(kind, recipient, notification));
        } catch (EmailDeliveryException error) {
            return error.permanent() ? failed("recipient_rejected") : retryOrFail(notification);
        } catch (RuntimeException error) {
            log.warn(
                "Closure notification {} was not sent: {}",
                notification.path("id").asLong(), error.getClass().getSimpleName()
            );
            return retryOrFail(notification);
        }

        ObjectNode sent = mapper.createObjectNode();
        sent.put("status", "sent");
        sent.put("sent_via", emailSender.name());
        sent.put("sent_at", Instant.now().toString());
        sent.putNull("locked_until");
        sent.putNull("last_error");

        return sent;
    }

    private EmailMessage message(String kind, String recipient, JsonNode notification) {
        if ("admin_review".equals(kind)) {
            return ClosureEmailTemplates.adminReview(
                recipient,
                notification.path("recipient_first_name").asText(""),
                notification.path("recipient_last_name").asText(""),
                notification.path("batch_reservations")
            );
        }

        ClosureEmailTemplates.Details details = new ClosureEmailTemplates.Details(
            notification.path("recipient_first_name").asText(""),
            notification.path("recipient_last_name").asText(""),
            notification.path("event_title").asText(""),
            Instant.parse(notification.path("event_start_time").asText()),
            Instant.parse(notification.path("event_end_time").asText()),
            notification.path("cause").asText(""),
            notification.path("closure_reason").asText(""),
            notification.path("review_status").asText(""),
            contactInstructions
        );

        return "canceled".equals(kind)
            ? ClosureEmailTemplates.canceled(recipient, details)
            : ClosureEmailTemplates.followUp(recipient, details, notification.path("follow_up_message").asText());
    }

    private ObjectNode retryOrFail(JsonNode notification) {
        int attempts = notification.path("attempts").asInt();

        if (attempts > RETRY_DELAYS.size()) {
            return failed("send_failed");
        }

        ObjectNode retry = mapper.createObjectNode();
        retry.put("status", "pending");
        retry.put("next_attempt_at", Instant.now().plus(RETRY_DELAYS.get(attempts - 1)).toString());
        retry.putNull("locked_until");
        retry.put("last_error", "send_failed");

        return retry;
    }

    private ObjectNode failed(String code) {
        ObjectNode failed = mapper.createObjectNode();
        failed.put("status", "failed");
        failed.putNull("locked_until");
        failed.put("last_error", code);

        return failed;
    }
}
