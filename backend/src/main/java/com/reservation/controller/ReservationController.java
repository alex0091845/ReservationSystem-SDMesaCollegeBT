package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class ReservationController {

    private final SupabaseClient supabase;
    private final ObjectMapper objectMapper;

    public ReservationController(SupabaseClient supabase, ObjectMapper objectMapper) {
        this.supabase = supabase;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("events?select=*,users(first_name,last_name)"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(supabase.get("events?id=eq." + id + "&select=*,users(first_name,last_name)"));
    }

    @GetMapping("/by-user/{userId}")
    public ResponseEntity<String> getByUser(@PathVariable int userId) {
        return ResponseEntity.ok(supabase.get("events?host_user_id=eq." + userId + "&select=*,users(first_name,last_name)"));
    }

    @GetMapping("/public")
    public ResponseEntity<String> getPublic() {
        return ResponseEntity.ok(supabase.get("events?is_public=eq.true&select=*,users(first_name,last_name)"));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        try {
            String eventBody = normalizeEventBody(body);
            return fromSupabase(supabase.postResponse("events", eventBody));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        try {
            String eventBody = normalizeEventBody(body);
            return fromSupabase(supabase.patchResponse("events?id=eq." + id, eventBody));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        supabase.delete("events?id=eq." + id);
        return ResponseEntity.noContent().build();
    }

    private String normalizeEventBody(String body) {
        try {
            JsonNode input = objectMapper.readTree(body);

            if (!input.isObject()) {
                throw new IllegalArgumentException("Event body must be a JSON object.");
            }

            ObjectNode event = objectMapper.createObjectNode();

            copyIntegerField(input, event, "host_user_id", "host_user_id", "user_id");
            copyField(input, event, "start_time", "start_time", "start");
            copyField(input, event, "end_time", "end_time", "end");
            copyField(input, event, "event_type", "event_type");
            copyField(input, event, "description", "description");
            copyField(input, event, "title", "title");
            copyField(input, event, "department", "department");
            copyBooleanField(input, event, "is_public", "is_public");

            validateEventBody(event);

            return objectMapper.writeValueAsString(event);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Event body could not be parsed.");
        }
    }

    private void copyField(JsonNode input, ObjectNode output, String outputField, String... inputFields) {
        JsonNode value = firstValue(input, inputFields);

        if (value != null && !value.isNull()) {
            output.set(outputField, value);
        }
    }

    private void copyIntegerField(JsonNode input, ObjectNode output, String outputField, String... inputFields) {
        JsonNode value = firstValue(input, inputFields);

        if (value == null || value.isNull()) {
            return;
        }

        if (value.isInt() || value.isLong()) {
            output.put(outputField, value.asLong());
            return;
        }

        if (value.isTextual()) {
            try {
                output.put(outputField, Long.parseLong(value.asText()));
                return;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(outputField + " must be a number.");
            }
        }

        throw new IllegalArgumentException(outputField + " must be a number.");
    }

    private void copyBooleanField(JsonNode input, ObjectNode output, String outputField, String... inputFields) {
        JsonNode value = firstValue(input, inputFields);

        if (value == null || value.isNull()) {
            return;
        }

        if (value.isBoolean()) {
            output.put(outputField, value.asBoolean());
            return;
        }

        if (value.isTextual()) {
            output.put(outputField, Boolean.parseBoolean(value.asText()));
            return;
        }

        throw new IllegalArgumentException(outputField + " must be true or false.");
    }

    private JsonNode firstValue(JsonNode input, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = input.get(fieldName);

            if (value != null && !value.isNull()) {
                return value;
            }
        }

        return null;
    }

    private void validateEventBody(ObjectNode event) {
        requireField(event, "host_user_id");
        requireField(event, "start_time");
        requireField(event, "end_time");
        requireField(event, "event_type");
        requireField(event, "description");
        requireField(event, "title");
        requireField(event, "department");
        requireField(event, "is_public");
    }

    private void requireField(ObjectNode event, String fieldName) {
        JsonNode value = event.get(fieldName);

        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response) {
        return ResponseEntity.status(response.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(response.body());
    }

    private ResponseEntity<String> jsonError(int status, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message);

        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(error.toString());
    }
}
