package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        return ResponseEntity.ok(supabase.post("attendees", buildAttendeePayload(body, true)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable long id, @RequestBody String body) {
        return ResponseEntity.ok(supabase.patch("attendees?id=eq." + id, buildAttendeePayload(body, false)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        supabase.delete("attendees?id=eq." + id);
        return ResponseEntity.noContent().build();
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

        ObjectNode payload = mapper.createObjectNode();

        if (input.hasNonNull("event_id")) {
            payload.put("event_id", input.path("event_id").asInt());
        }
        if (input.hasNonNull("sdccd_id")) {
            payload.put("sdccd_id", input.path("sdccd_id").asInt());
        }
        copyTextIfPresent(input, payload, "first_name");
        copyTextIfPresent(input, payload, "last_name");
        copyTextIfPresent(input, payload, "email");

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

    private void copyTextIfPresent(JsonNode input, ObjectNode payload, String field) {
        if (input.hasNonNull(field)) {
            payload.put(field, input.path(field).asText());
        }
    }
}
