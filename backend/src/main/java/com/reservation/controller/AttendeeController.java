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

@RestController
@RequestMapping("/api/attendees")
public class AttendeeController {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;

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
        return fromSupabase(supabase.postResponse("attendees", buildAttendeePayload(body, true)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable long id, @RequestBody String body) {
        return fromSupabase(supabase.patchResponse(
            "attendees?id=eq." + id, buildAttendeePayload(body, false)
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
    private String buildAttendeePayload(String body, boolean isCreate) {
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
        }

        if (payload.isEmpty()) {
            throw new IllegalArgumentException("No updatable fields provided");
        }

        return payload.toString();
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
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"The database could not complete this request.\"}");
        }
        return ResponseEntity.status(response.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(response.body());
    }
}
