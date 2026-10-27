package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Translates the calendar rule errors raised by the database into API responses.
 *
 * <p>The database (migration {@code 20261021_001}) is the authority on semesters,
 * unavailable days, and admin overrides: its triggers judge every write, including
 * two requests racing for the same day. Each refusal carries its own SQLSTATE and a
 * JSON DETAIL, and this class turns those into the project's {@code {"error": ...}}
 * responses. Only fields the database put there on purpose are copied through.
 */
final class CalendarRuleErrors {

    static final String RESERVATION_DATE_RESTRICTED = "BTC01";
    static final String CLOSURE_HAS_RESERVATIONS = "BTC02";
    static final String SEMESTER_CHANGE_STRANDS_RECORDS = "BTC03";
    static final String CLOSURE_OUTSIDE_SEMESTER = "BTC04";
    static final String CLOSURE_IN_PAST = "BTC05";
    static final String OVERRIDE_NOT_ALLOWED = "BTA01";

    static final String UNIQUE_VIOLATION = "23505";
    static final String CHECK_VIOLATION = "23514";
    static final String EXCLUSION_VIOLATION = "23P01";

    static final String OVERRIDE_FORBIDDEN_MESSAGE = "Only administrators can override calendar restrictions.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CalendarRuleErrors() {
    }

    /**
     * @return the response for a calendar rule refusal, or {@code null} when the
     *         failure is something else and the caller should handle it
     */
    static ResponseEntity<String> toResponse(SupabaseClient.SupabaseResponse response) {
        JsonNode details = response.errorDetails();

        return switch (response.errorCode()) {
            case RESERVATION_DATE_RESTRICTED -> reservationDateRestricted(details);
            case CLOSURE_HAS_RESERVATIONS -> closureHasReservations(details);
            case SEMESTER_CHANGE_STRANDS_RECORDS -> withDetails(
                409,
                "This change would leave reservations or unavailable days outside every semester.",
                details,
                "semester_id", "reservations", "unavailable_days"
            );
            case CLOSURE_OUTSIDE_SEMESTER -> withDetails(
                409, "Unavailable days must fall within a configured semester.", details, "date", "semester_id"
            );
            case CLOSURE_IN_PAST -> withDetails(
                400, "Unavailable days cannot be set in the past.", details, "date"
            );
            case OVERRIDE_NOT_ALLOWED -> jsonError(403, OVERRIDE_FORBIDDEN_MESSAGE);
            default -> null;
        };
    }

    // The first conflicting date is repeated as "date" so a single-reservation
    // client can show it directly; "dates" lists every one for batch and recurring.
    private static ResponseEntity<String> reservationDateRestricted(JsonNode details) {
        boolean hasClosedDay = details.path("unavailable_dates").isArray() &&
            !details.path("unavailable_dates").isEmpty();
        String message = hasClosedDay || !details.path("outside_semester_dates").isArray()
            ? "Reservation date is unavailable."
            : "Reservation date is outside the configured semester.";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("error", message);

        JsonNode dates = details.path("dates");
        if (dates.isArray() && !dates.isEmpty()) {
            body.set("date", dates.get(0));
        }
        copy(details, body, "dates", "unavailable_dates", "outside_semester_dates");

        return json(409, body);
    }

    // First step of an emergency closure: the admin sees who is booked, then can repeat the
    // request with admin_override and override_reason to close the day anyway.
    private static ResponseEntity<String> closureHasReservations(JsonNode details) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("error", "Reservations already exist on this date.");
        copy(details, body, "date", "reservations");
        body.put("override_available", true);

        return json(409, body);
    }

    private static ResponseEntity<String> withDetails(int status, String message, JsonNode details, String... fields) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("error", message);
        copy(details, body, fields);

        return json(status, body);
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field));
            }
        }
    }

    static ResponseEntity<String> jsonError(int status, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("error", message);

        return json(status, error);
    }

    private static ResponseEntity<String> json(int status, ObjectNode body) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body.toString());
    }
}
