package com.reservation.notifications;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ClosureEmailTemplatesTest {

    private static final String CONTACT = "Call the front desk.";
    private static final Instant START = Instant.parse("2026-11-20T22:00:00Z");
    private static final Instant END = Instant.parse("2026-11-20T23:30:00Z");

    private static ClosureEmailTemplates.Details details(String cause, String reason, String reviewStatus) {
        return new ClosureEmailTemplates.Details(
            "Ada", "Lovelace", "Robotics Club", START, END, cause, reason, reviewStatus, CONTACT
        );
    }

    private static String canceled(String cause, String reason, String reviewStatus) {
        return ClosureEmailTemplates.canceled("ada@sdmesa.edu", details(cause, reason, reviewStatus)).body();
    }

    @Test
    void cancellationSaysWhatHappenedAndIncludesTheDetails() {
        EmailMessage message = ClosureEmailTemplates.canceled(
            "ada@sdmesa.edu", details("unavailable_date", "Water leak", "pending")
        );

        assertEquals("ada@sdmesa.edu", message.to());
        assertEquals(ClosureEmailTemplates.CANCELED_SUBJECT, message.subject());
        assertTrue(message.body().startsWith("Hello Ada Lovelace,"));
        assertTrue(message.body().contains("\"Robotics Club\""));
        assertTrue(message.body().contains("Friday, November 20, 2026 at 2:00 PM to 3:30 PM PST"));
        assertTrue(message.body().contains("has been canceled"));
        assertTrue(message.body().contains("Reason given: Water leak"));
        assertTrue(message.body().contains(CONTACT));
    }

    @Test
    void facultyNoticeSaysItIsSystemGeneratedAndNotYetReviewed() {
        String body = canceled("unavailable_date", "", "pending");

        assertTrue(body.contains("system-generated"));
        assertTrue(body.contains("have not yet been reviewed by an administrator"));
        assertTrue(body.contains("may still be pending"));
        assertTrue(body.contains("an administrator will notify you by email"));
        assertFalse(body.contains("has reviewed"));
    }

    @Test
    void aFinishedReviewIsNotDescribedAsPending() {
        String body = canceled("unavailable_date", "", "reviewed");

        assertTrue(body.contains("An administrator has reviewed this cancellation."));
        assertFalse(body.contains("not yet been reviewed"));
        assertFalse(body.contains("still be pending"));
        assertTrue(body.contains("has been canceled"));
    }

    @Test
    void theCauseMatchesWhatTheAdminDid() {
        assertTrue(canceled("unavailable_date", "", "pending").contains("marked unavailable on that date"));

        String cutoff = canceled("after_last_bookable_date", "", "pending");

        assertTrue(cutoff.contains("after the last bookable date"));
        assertFalse(cutoff.contains("marked unavailable"));
    }

    @Test
    void aMissingReasonLeavesNoEmptyLine() {
        assertFalse(canceled("unavailable_date", "", "pending").contains("Reason given"));
    }

    @Test
    void freeTextCannotAddLinesToTheMessage() {
        String body = ClosureEmailTemplates.canceled("ada@sdmesa.edu", new ClosureEmailTemplates.Details(
            "Ada\r\nBcc: x", "", "Club\nFake line", START, END, "unavailable_date", "Leak\r\nFake", "pending", CONTACT
        )).body();

        assertTrue(body.contains("Hello Ada Bcc: x,"));
        assertTrue(body.contains("\"Club Fake line\""));
        assertTrue(body.contains("Reason given: Leak Fake"));
    }

    @Test
    void aMissingTitleAndNameStillReadWell() {
        String body = ClosureEmailTemplates.canceled("ada@sdmesa.edu", new ClosureEmailTemplates.Details(
            "", "", "", START, END, "unavailable_date", "", "pending", CONTACT
        )).body();

        assertTrue(body.startsWith("Hello,"));
        assertTrue(body.contains("Your BT-216 reservation scheduled for"));
    }

    @Test
    void daylightTimeIsShownForSummerReservations() {
        String body = ClosureEmailTemplates.canceled("ada@sdmesa.edu", new ClosureEmailTemplates.Details(
            "", "", "", Instant.parse("2027-07-01T18:00:00Z"), Instant.parse("2027-07-01T19:00:00Z"),
            "unavailable_date", "", "pending", CONTACT
        )).body();

        assertTrue(body.contains("11:00 AM to 12:00 PM PDT"));
    }

    @Test
    void followUpIsFramedAsAnAdminsOwnWordsAndKeepsLineBreaks() {
        EmailMessage message = ClosureEmailTemplates.followUp(
            "ada@sdmesa.edu", details("unavailable_date", "", "reviewed"), "Room 301 is free.\r\nSee you there."
        );

        assertEquals(ClosureEmailTemplates.FOLLOW_UP_SUBJECT, message.subject());
        assertTrue(message.body().contains("An administrator reviewed your canceled BT-216 reservation"));
        assertTrue(message.body().contains("Room 301 is free.\nSee you there."));
        assertTrue(message.body().contains("remains canceled unless the update above says otherwise"));
        assertFalse(message.body().contains("system-generated"));
    }

    @Test
    void adminSummaryListsEachReservationWithItsReviewState() {
        JsonNode reservations = new ObjectMapper().readTree("""
            [{"review_id":3,"title":"Robotics Club","start_time":"2026-11-20T22:00:00+00:00",
              "end_time":"2026-11-20T23:30:00+00:00","host_first_name":"Grace","host_last_name":"Hopper",
              "cause":"unavailable_date","closure_reason":"Water leak","review_status":"pending"},
             {"review_id":4,"title":null,"start_time":"2026-11-21T22:00:00+00:00",
              "end_time":"2026-11-21T23:00:00+00:00","host_first_name":null,"host_last_name":null,
              "cause":"after_last_bookable_date","closure_reason":null,"review_status":"reviewed"}]
            """);

        EmailMessage message = ClosureEmailTemplates.adminReview("admin@sdmesa.edu", "Sam", "Second", reservations);

        assertEquals(ClosureEmailTemplates.ADMIN_REVIEW_SUBJECT, message.subject());
        String body = message.body();
        assertTrue(body.startsWith("Hello Sam Second,"));
        assertTrue(body.contains("- Robotics Club\n  When: Friday, November 20, 2026 at 2:00 PM to 3:30 PM PST\n"));
        assertTrue(body.contains("  Organizer: Grace Hopper\n"));
        assertTrue(body.contains("  Reason given: Water leak\n"));
        assertTrue(body.contains("  Review: pending (review 3)\n"));
        assertTrue(body.contains("- Untitled reservation\n"));
        assertTrue(body.contains("  Organizer: Unknown\n"));
        assertTrue(body.contains("after the last bookable date"));
        assertTrue(body.contains("  Review: completed (review 4)\n"));
        assertFalse(body.contains("null"));
        assertTrue(body.contains("Please review the pending reservations"));
    }

    @Test
    void adminSummaryDoesNotAskForReviewsThatAreAlreadyDone() {
        JsonNode reservations = new ObjectMapper().readTree("""
            [{"review_id":4,"title":"Lab","start_time":"2026-11-21T22:00:00+00:00",
              "end_time":"2026-11-21T23:00:00+00:00","cause":"unavailable_date","review_status":"reviewed"}]
            """);

        String body = ClosureEmailTemplates.adminReview("admin@sdmesa.edu", "", "", reservations).body();

        assertTrue(body.contains("All of these have already been reviewed."));
        assertFalse(body.contains("Please review"));
    }
}
