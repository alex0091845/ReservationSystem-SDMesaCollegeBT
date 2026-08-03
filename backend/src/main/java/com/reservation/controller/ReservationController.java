package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/events")
public class ReservationController {

    private static final ZoneId RESERVATION_TIME_ZONE = ZoneId.of("America/Los_Angeles");
    private static final LocalTime RESERVATION_DAY_END = LocalTime.of(17, 0);

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

    @PostMapping("/batch")
    public ResponseEntity<String> createBatch(@RequestBody String body, HttpServletRequest request) {
        try {
            JsonNode input = objectMapper.readTree(body);
            JsonNode inputEvents = input.isArray() ? input : input.path("events");

            if (!inputEvents.isArray() || inputEvents.isEmpty()) {
                return jsonError(400, "At least one reservation is required.");
            }

            ArrayNode eventBodies = objectMapper.createArrayNode();

            for (int index = 0; index < inputEvents.size(); index++) {
                ObjectNode eventBody;

                try {
                    eventBody = normalizeEventInput(inputEvents.get(index));
                } catch (IllegalArgumentException error) {
                    return jsonError(400, "Reservation " + (index + 1) + ": " + error.getMessage());
                }

                if (!canSaveWithRequestedHost(request, eventBody)) {
                    return jsonError(403, "You do not have permission to save reservation " + (index + 1) + " for that host.");
                }

                eventBodies.add(eventBody);
            }

            return fromSupabase(supabase.postResponse("events", objectMapper.writeValueAsString(eventBodies)));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @PostMapping("/recurring")
    public ResponseEntity<String> createRecurring(@RequestBody String body, HttpServletRequest request) {
        try {
            JsonNode input = objectMapper.readTree(body);

            if (input == null || !input.isObject()) {
                return jsonError(400, "Recurring reservation body must be a JSON object.");
            }

            JsonNode eventTemplate = input.path("event");
            JsonNode ranges = input.path("ranges");

            if (!eventTemplate.isObject()) {
                return jsonError(400, "Recurring reservation event details are required.");
            }

            if (!ranges.isArray() || ranges.isEmpty()) {
                return jsonError(400, "At least one recurring reservation time range is required.");
            }

            ArrayNode eventBodies = objectMapper.createArrayNode();

            for (int rangeIndex = 0; rangeIndex < ranges.size(); rangeIndex++) {
                JsonNode range = ranges.get(rangeIndex);

                if (!range.isObject()) {
                    return jsonError(400, "Recurring reservation range " + (rangeIndex + 1) + " is invalid.");
                }

                ZonedDateTime baseStart = parseReservationDateTime(
                    requiredText(range, "start_time", "Recurring reservation start time is required."),
                    "start_time"
                );
                ZonedDateTime baseEnd = parseReservationDateTime(
                    requiredText(range, "end_time", "Recurring reservation end time is required."),
                    "end_time"
                );
                int weekCount = readWeekCount(range.path("week_count"));
                Set<Integer> excludedWeekOffsets = readExcludedWeekOffsets(
                    range.path("excluded_week_offsets"),
                    weekCount
                );

                for (int weekOffset = 0; weekOffset < weekCount; weekOffset++) {
                    if (excludedWeekOffsets.contains(weekOffset)) {
                        continue;
                    }

                    ObjectNode occurrenceInput = ((ObjectNode) eventTemplate).deepCopy();
                    occurrenceInput.put("start_time", baseStart.plusWeeks(weekOffset).toInstant().toString());
                    occurrenceInput.put("end_time", baseEnd.plusWeeks(weekOffset).toInstant().toString());

                    ObjectNode eventBody;

                    try {
                        eventBody = normalizeEventInput(occurrenceInput);
                    } catch (IllegalArgumentException error) {
                        return jsonError(
                            400,
                            "Recurring reservation range " + (rangeIndex + 1) +
                                ", week " + (weekOffset + 1) + ": " + error.getMessage()
                        );
                    }

                    if (!canSaveWithRequestedHost(request, eventBody)) {
                        return jsonError(403, "You do not have permission to save this recurring reservation for that host.");
                    }

                    eventBodies.add(eventBody);
                }
            }

            return fromSupabase(supabase.postResponse("events", objectMapper.writeValueAsString(eventBodies)));
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

            return normalizeEventInput(input);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Event body could not be parsed.");
        }
    }

    private ObjectNode normalizeEventInput(JsonNode input) {
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
        copyField(input, event, "recurrence_group_id", "recurrence_group_id");

        validateEventBody(event);

        return event;
    }

    private String requiredText(JsonNode input, String fieldName, String message) {
        JsonNode value = input.get(fieldName);

        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.asText();
    }

    private int readWeekCount(JsonNode input) {
        if (!input.isIntegralNumber() || !input.canConvertToInt() || input.asInt() < 1) {
            throw new IllegalArgumentException("Recurring reservation week_count must be a positive whole number.");
        }

        return input.asInt();
    }

    private Set<Integer> readExcludedWeekOffsets(JsonNode input, int weekCount) {
        Set<Integer> weekOffsets = new LinkedHashSet<>();

        if (input.isMissingNode() || input.isNull()) {
            return weekOffsets;
        }

        if (!input.isArray()) {
            throw new IllegalArgumentException("Recurring reservation excluded week offsets must be an array.");
        }

        for (JsonNode value : input) {
            if (
                !value.isIntegralNumber() ||
                !value.canConvertToInt() ||
                value.asInt() < 0 ||
                value.asInt() >= weekCount
            ) {
                throw new IllegalArgumentException(
                    "Recurring reservation excluded week offsets must fall within the requested duration."
                );
            }

            weekOffsets.add(value.asInt());
        }

        if (weekOffsets.size() == weekCount) {
            throw new IllegalArgumentException("A recurring reservation cannot exclude every week.");
        }

        return weekOffsets;
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

        validateReservationTimeWindow(event);
    }

    private void validateReservationTimeWindow(ObjectNode event) {
        ZonedDateTime startTime = parseReservationDateTime(
            event.path("start_time").asText(),
            "start_time"
        );
        ZonedDateTime endTime = parseReservationDateTime(
            event.path("end_time").asText(),
            "end_time"
        );

        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("End time must be after start time.");
        }

        if (!startTime.toLocalTime().isBefore(RESERVATION_DAY_END)) {
            throw new IllegalArgumentException("Start time must be before 5:00 PM.");
        }

        if (endTime.toLocalTime().isAfter(RESERVATION_DAY_END)) {
            throw new IllegalArgumentException("Reservations must end by 5:00 PM.");
        }
    }

    private ZonedDateTime parseReservationDateTime(String value, String fieldName) {
        try {
            return Instant.parse(value).atZone(RESERVATION_TIME_ZONE);
        } catch (DateTimeParseException ignored) {
            // Try a broader set of accepted timestamp shapes below.
        }

        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(RESERVATION_TIME_ZONE);
        } catch (DateTimeParseException ignored) {
            // Try local date-time as a final fallback.
        }

        try {
            return LocalDateTime.parse(value).atZone(RESERVATION_TIME_ZONE);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(fieldName + " must be a valid date and time.");
        }
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
