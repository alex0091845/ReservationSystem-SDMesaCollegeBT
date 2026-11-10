package com.reservation.notifications;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The closure emails. Only the {@code canceled} notice says a reservation was canceled, because it
 * is only queued in the transaction that deletes one. Whether a review has happened is decided by
 * the review row at send time, never assumed.
 */
final class ClosureEmailTemplates {

    static final String CANCELED_SUBJECT = "Important: BT-216 Reservation Canceled";
    static final String ADMIN_REVIEW_SUBJECT = "Review Needed: BT-216 Reservations Canceled";
    static final String FOLLOW_UP_SUBJECT = "Update From an Administrator: BT-216 Reservation";

    private static final String REVIEWED = "reviewed";
    private static final String AFTER_CUTOFF = "after_last_bookable_date";
    private static final ZoneId CALENDAR_TIME_ZONE = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter TIME_AND_ZONE = DateTimeFormatter.ofPattern("h:mm a z", Locale.US);
    private static final String SIGNATURE = """
        BT-216 Reservation System
        San Diego Mesa College
        """;

    /** What the host's emails need. {@code reason} is the admin's closure reason and may be empty. */
    record Details(
        String firstName,
        String lastName,
        String title,
        Instant start,
        Instant end,
        String cause,
        String reason,
        String reviewStatus,
        String contact
    ) {
    }

    private ClosureEmailTemplates() {
    }

    static EmailMessage canceled(String to, Details details) {
        String review = REVIEWED.equals(details.reviewStatus())
            ? "An administrator has reviewed this cancellation. If anything changes, an administrator " +
                "will notify you by email.\n\n"
            : "Your individual circumstances have not yet been reviewed by an administrator, and that " +
                "follow-up review may still be pending. If anything changes after the review, an " +
                "administrator will notify you by email.\n\n";
        String body = greeting(details) +
            "This is a system-generated message from the BT-216 Reservation System.\n\n" +
            "Your BT-216 reservation" + quotedTitle(details.title()) + " scheduled for " +
            when(details.start(), details.end()) + " has been canceled because " +
            cause(details.cause()) + ".\n\n" +
            reasonLine(details.reason()) +
            review +
            clean(details.contact()) + "\n\n" +
            SIGNATURE;

        return new EmailMessage(to, CANCELED_SUBJECT, body);
    }

    static EmailMessage followUp(String to, Details details, String message) {
        String body = greeting(details) +
            "An administrator reviewed your canceled BT-216 reservation" + quotedTitle(details.title()) +
            " originally scheduled for " + when(details.start(), details.end()) +
            " and wrote this update:\n\n" +
            cleanLines(message) + "\n\n" +
            "The original reservation remains canceled unless the update above says otherwise.\n\n" +
            clean(details.contact()) + "\n\n" +
            SIGNATURE;

        return new EmailMessage(to, FOLLOW_UP_SUBJECT, body);
    }

    /** {@code reservations} comes from {@code claim_closure_notifications}. Admins only. */
    static EmailMessage adminReview(String to, String firstName, String lastName, JsonNode reservations) {
        StringBuilder body = new StringBuilder(greeting(firstName, lastName));
        body.append("This is a system-generated message from the BT-216 Reservation System.\n\n");
        body.append("A confirmed availability change canceled the reservations below. Each organizer gets ");
        body.append("an automatic notice saying their individual circumstances may still need review.\n\n");

        for (JsonNode reservation : reservations) {
            String title = clean(reservation.path("title").asText(""));
            String host = (clean(reservation.path("host_first_name").asText("")) + " " +
                clean(reservation.path("host_last_name").asText(""))).trim();
            String reason = clean(reservation.path("closure_reason").asText(""));

            body.append("- ").append(title.isEmpty() ? "Untitled reservation" : title).append('\n');
            body.append("  When: ").append(when(
                Instant.parse(reservation.path("start_time").asText()),
                Instant.parse(reservation.path("end_time").asText())
            )).append('\n');
            body.append("  Organizer: ").append(host.isEmpty() ? "Unknown" : host).append('\n');
            body.append("  Canceled because ").append(cause(reservation.path("cause").asText(""))).append('\n');
            if (!reason.isEmpty()) {
                body.append("  Reason given: ").append(reason).append('\n');
            }
            body.append("  Review: ").append(REVIEWED.equals(reservation.path("review_status").asText())
                ? "completed" : "pending").append(" (review ").append(reservation.path("review_id").asLong()).append(")\n\n");
        }

        boolean anyPending = false;
        for (JsonNode reservation : reservations) {
            anyPending |= !REVIEWED.equals(reservation.path("review_status").asText());
        }
        body.append(anyPending
            ? "Please review the pending reservations and mark each one as reviewed. "
            : "All of these have already been reviewed. ");
        body.append("If a decision changes, the organizer should hear about it from you; nothing is sent to ");
        body.append("them automatically.\n\n");
        body.append(SIGNATURE);

        return new EmailMessage(to, ADMIN_REVIEW_SUBJECT, body.toString());
    }

    private static String greeting(Details details) {
        return greeting(details.firstName(), details.lastName());
    }

    private static String greeting(String firstName, String lastName) {
        String name = (clean(firstName) + " " + clean(lastName)).trim();

        return name.isEmpty() ? "Hello,\n\n" : "Hello " + name + ",\n\n";
    }

    private static String quotedTitle(String value) {
        String title = clean(value);

        return title.isEmpty() ? "" : " \"" + title + "\"";
    }

    private static String when(Instant start, Instant end) {
        return start.atZone(CALENDAR_TIME_ZONE).format(DATE) + " at " +
            start.atZone(CALENDAR_TIME_ZONE).format(TIME) + " to " +
            end.atZone(CALENDAR_TIME_ZONE).format(TIME_AND_ZONE);
    }

    private static String cause(String cause) {
        return AFTER_CUTOFF.equals(cause)
            ? "the date is now after the last bookable date for BT-216"
            : "BT-216 has been marked unavailable on that date";
    }

    private static String reasonLine(String value) {
        String reason = clean(value);

        return reason.isEmpty() ? "" : "Reason given: " + reason + "\n\n";
    }

    // Titles and reasons are free text: keep them on one line of the message.
    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}+", " ").trim();
    }

    // An admin's follow-up keeps its line breaks.
    private static String cleanLines(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n]]+", " ").trim();
    }
}
