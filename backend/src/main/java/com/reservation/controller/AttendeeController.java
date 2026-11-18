package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.config.SupabaseClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/attendees")
public class AttendeeController {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    // Check-in opens this long before an event starts and closes this long after it ends (C1 #71, C2 #102)
    private static final Duration CHECK_IN_WINDOW = Duration.ofMinutes(15);
    private static final String DUPLICATE_CHECK_IN_MESSAGE = "This email is already checked in to this event.";
    private static final String POSTGRES_UNIQUE_VIOLATION = "23505";

    private final SupabaseClient supabase;
    private final ObjectMapper mapper = new ObjectMapper();

    public AttendeeController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("attendees?select=*,events(title)"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable long id) {
        return ResponseEntity.ok(supabase.get("attendees?id=eq." + id + "&select=*,events(title)"));
    }

    @GetMapping("/by-event/{eventId}")
    public ResponseEntity<String> getByEvent(@PathVariable int eventId) {
        return ResponseEntity.ok(supabase.get("attendees?event_id=eq." + eventId + "&select=*,events(title)"));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        ObjectNode payload = buildAttendeePayload(body, true);
        int eventId = payload.path("event_id").asInt();
        JsonNode event = findEvent(eventId);

        if (event == null) {
            return jsonError(404, "Event not found.");
        }

        String closedReason = checkInClosedReason(event);

        if (closedReason != null) {
            return jsonError(409, closedReason);
        }

        if (isAlreadyCheckedIn(eventId, payload.path("email").asText())) {
            return jsonError(409, DUPLICATE_CHECK_IN_MESSAGE);
        }

        return fromSupabase(supabase.postResponse("attendees", payload.toString()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable long id, @RequestBody String body) {
        return fromSupabase(supabase.patchResponse(
            "attendees?id=eq." + id, buildAttendeePayload(body, false).toString()
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        return DeleteOutcome.toResponse(
            supabase.delete("attendees?id=eq." + id),
            "This check-in is still referenced elsewhere, so it cannot be deleted."
        );
    }

    /**
     * Whitelist the columns a client may set on the {@code attendees} table. This endpoint
     * is public (unauthenticated sign-up), so raw client JSON must never be forwarded
     * straight to PostgREST — only known columns are accepted. {@code check_in_time} is
     * left to the table's default (now()).
     */
    private ObjectNode buildAttendeePayload(String body, boolean isCreate) {
        JsonNode input;
        try {
            input = mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed request body");
        }

        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("Attendee body must be a JSON object");
        }

        ObjectNode payload = mapper.createObjectNode();

        if (input.hasNonNull("event_id")) {
            payload.put("event_id", readPositiveInteger(input.get("event_id"), "event_id"));
        }
        if (input.hasNonNull("sdccd_id")) {
            payload.put("sdccd_id", readPositiveInteger(input.get("sdccd_id"), "sdccd_id"));
        }
        copyTextIfPresent(input, payload, "first_name", MAX_NAME_LENGTH);
        copyTextIfPresent(input, payload, "last_name", MAX_NAME_LENGTH);
        copyTextIfPresent(input, payload, "email", MAX_EMAIL_LENGTH);

        if (isCreate) {
            if (!payload.has("event_id")) {
                throw new IllegalArgumentException("event_id is required");
            }
            if (payload.path("first_name").asText("").isBlank()) {
                throw new IllegalArgumentException("first_name is required");
            }
            // Duplicate check-ins are detected by email, so a check-in must have one
            if (payload.path("email").asText("").isBlank()) {
                throw new IllegalArgumentException("email is required");
            }
        }

        if (payload.has("email") && !EMAIL_PATTERN.matcher(payload.path("email").asText()).matches()) {
            throw new IllegalArgumentException("email must be a valid email address");
        }

        if (payload.isEmpty()) {
            throw new IllegalArgumentException("No updatable fields provided");
        }

        return payload;
    }

    private JsonNode findEvent(int eventId) {
        JsonNode events = mapper.readTree(supabase.get(
            "events?id=eq." + eventId + "&select=start_time,end_time,is_closure,cancelled_at"
        ));

        return events.isArray() && !events.isEmpty() ? events.get(0) : null;
    }

    // The server's clock decides when check-in is open, not the page. Returns null when open.
    private String checkInClosedReason(JsonNode event) {
        if (event.path("is_closure").asBoolean(false)) {
            return "Unavailable dates cannot accept check-ins.";
        }

        if (event.hasNonNull("cancelled_at")) {
            return "This event has been cancelled.";
        }

        Instant now = Instant.now();
        Instant opensAt = OffsetDateTime.parse(event.path("start_time").asText()).toInstant().minus(CHECK_IN_WINDOW);
        Instant closesAt = OffsetDateTime.parse(event.path("end_time").asText()).toInstant().plus(CHECK_IN_WINDOW);

        if (now.isBefore(opensAt)) {
            return "Check-in opens " + CHECK_IN_WINDOW.toMinutes() + " minutes before the event starts.";
        }

        if (now.isAfter(closesAt)) {
            return "Check-in for this event has closed.";
        }

        return null;
    }

    // Compared here rather than with a PostgREST ilike filter, where % and _ in the submitted
    // email would act as wildcards. The unique index on (event_id, lower(email)) backs this up
    // when two submits arrive at the same moment.
    private boolean isAlreadyCheckedIn(int eventId, String email) {
        JsonNode checkIns = mapper.readTree(supabase.get("attendees?event_id=eq." + eventId + "&select=email"));

        for (JsonNode checkIn : checkIns) {
            if (email.equalsIgnoreCase(checkIn.path("email").asText("").trim())) {
                return true;
            }
        }

        return false;
    }

    private void copyTextIfPresent(JsonNode input, ObjectNode payload, String field, int maxLength) {
        if (input.hasNonNull(field)) {
            String value = input.path(field).asText();
            if (value.length() > maxLength) {
                throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
            }
            payload.put(field, value.trim());
        }
    }

    private int readPositiveInteger(JsonNode value, String field) {
        if (value == null || !value.canConvertToInt() || value.asInt() < 1) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return value.asInt();
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response) {
        if (!response.isSuccessful()) {
            if (isUniqueViolation(response)) {
                return jsonError(409, DUPLICATE_CHECK_IN_MESSAGE);
            }
            return jsonError(502, "The database could not complete this request.");
        }
        return ResponseEntity.status(response.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(response.body());
    }

    private boolean isUniqueViolation(SupabaseClient.SupabaseResponse response) {
        try {
            return POSTGRES_UNIQUE_VIOLATION.equals(mapper.readTree(response.body()).path("code").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private ResponseEntity<String> jsonError(int status, String message) {
        ObjectNode error = mapper.createObjectNode();
        error.put("error", message);

        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(error.toString());
    }
}
