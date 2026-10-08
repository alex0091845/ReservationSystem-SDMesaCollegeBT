package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/event-types")
public class EventTypeController {

    private final SupabaseClient supabase;
    private final ObjectMapper mapper = new ObjectMapper();
    public EventTypeController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("event_types?select=*"));
    }

    @GetMapping("/{eventType}")
    public ResponseEntity<String> getByType(@PathVariable String eventType) {
        return ResponseEntity.ok(supabase.get("event_types?event_type=eq." + encode(eventType)));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return fromSupabase(supabase.postResponse("event_types", buildPayload(body, true)));
    }

    @PatchMapping("/{eventType}")
    public ResponseEntity<String> update(@PathVariable String eventType, @RequestBody String body) {
        return fromSupabase(supabase.patchResponse(
            "event_types?event_type=eq." + encode(eventType), buildPayload(body, false)
        ));
    }

    @DeleteMapping("/{eventType}")
    public ResponseEntity<?> delete(@PathVariable String eventType) {
        return DeleteOutcome.toResponse(
            supabase.delete("event_types?event_type=eq." + encode(eventType)),
            "Reservations are still using this event type, so it cannot be deleted."
        );
    }

    private String buildPayload(String body, boolean isCreate) {
        try {
            JsonNode input = mapper.readTree(body);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Event type body must be a JSON object");
            }
            ObjectNode payload = mapper.createObjectNode();
            copyText(input, payload, "event_type");
            copyText(input, payload, "description");
            if (isCreate && payload.path("event_type").asText("").isBlank()) {
                throw new IllegalArgumentException("event_type is required");
            }
            if (payload.isEmpty()) {
                throw new IllegalArgumentException("No updatable fields provided");
            }
            return payload.toString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Malformed event type body");
        }
    }

    private void copyText(JsonNode input, ObjectNode payload, String field) {
        if (input.hasNonNull(field) && input.get(field).isTextual()) {
            payload.put(field, input.path(field).asText());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
