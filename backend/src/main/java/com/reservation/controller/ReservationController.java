package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class ReservationController {

    private final SupabaseClient supabase;
    private final ObjectMapper objectMapper;
    private final AuthService authService;

    public ReservationController(SupabaseClient supabase, ObjectMapper objectMapper, AuthService authService) {
        this.supabase = supabase;
        this.objectMapper = objectMapper;
        this.authService = authService;
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
    public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest request) {
        try {
            ObjectNode eventBody = normalizeEventBody(body);

            if (!canSaveWithRequestedHost(request, eventBody)) {
                return jsonError(403, "You do not have permission to save this reservation for that host.");
            }

            return fromSupabase(supabase.postResponse("events", objectMapper.writeValueAsString(eventBody)));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body, HttpServletRequest request) {
        try {
            JsonNode existingEvent = getExistingEvent(id);

            if (existingEvent == null) {
                return jsonError(404, "Reservation not found.");
            }

            if (!canModifyExistingEvent(request, existingEvent)) {
                return jsonError(403, "You do not have permission to modify this reservation.");
            }

            ObjectNode eventBody = normalizeEventBody(body);

            if (!canSaveWithRequestedHost(request, eventBody)) {
                return jsonError(403, "You do not have permission to save this reservation for that host.");
            }

            return fromSupabase(supabase.patchResponse("events?id=eq." + id, objectMapper.writeValueAsString(eventBody)));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable int id, HttpServletRequest request) {
        JsonNode existingEvent = getExistingEvent(id);

        if (existingEvent == null) {
            return jsonError(404, "Reservation not found.");
        }

        if (!canModifyExistingEvent(request, existingEvent)) {
            return jsonError(403, "You do not have permission to delete this reservation.");
        }

        supabase.delete("events?id=eq." + id);
        return ResponseEntity.noContent().build();
    }

    private ObjectNode normalizeEventBody(String body) {
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

            return event;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Event body could not be parsed.");
        }
    }

    private JsonNode getExistingEvent(int id) {
        try {
            String response = supabase.get("events?id=eq." + id + "&select=id,host_user_id");
            JsonNode events = objectMapper.readTree(response);

            if (!events.isArray() || events.isEmpty()) {
                return null;
            }

            return events.get(0);
        } catch (Exception error) {
            throw new RuntimeException("Could not verify reservation ownership.");
        }
    }

    private boolean canModifyExistingEvent(HttpServletRequest request, JsonNode existingEvent) {
        JsonNode currentUser = getCurrentUser(request);

        if (currentUser == null) {
            return false;
        }

        if (authService.isAdmin(currentUser)) {
            return true;
        }

        return userOwnsHostId(currentUser, existingEvent.path("host_user_id"));
    }

    private boolean canSaveWithRequestedHost(HttpServletRequest request, ObjectNode eventBody) {
        JsonNode currentUser = getCurrentUser(request);

        if (currentUser == null) {
            return false;
        }

        if (authService.isAdmin(currentUser)) {
            return true;
        }

        return userOwnsHostId(currentUser, eventBody.path("host_user_id"));
    }

    private JsonNode getCurrentUser(HttpServletRequest request) {
        Object currentUser = request.getAttribute("currentUser");

        return currentUser instanceof JsonNode ? (JsonNode) currentUser : null;
    }

    private boolean userOwnsHostId(JsonNode currentUser, JsonNode hostUserId) {
        if (!hostUserId.canConvertToLong() || !currentUser.path("id").canConvertToLong()) {
            return false;
        }

        return hostUserId.asLong() == currentUser.path("id").asLong();
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
