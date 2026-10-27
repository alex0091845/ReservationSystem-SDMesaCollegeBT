package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Semester boundaries for BT-216. Faculty can only book dates inside a semester;
 * the database enforces that (see {@link CalendarRuleErrors}).
 *
 * <p>Reads are public so the calendar can grey out closed periods. Writes are
 * admin-only, enforced by {@code AuthInterceptor}.
 */
@RestController
@RequestMapping("/api/semesters")
public class SemesterController {

    private static final String PUBLIC_COLUMNS = "id,name,start_date,end_date";
    private static final int MAX_NAME_LENGTH = 100;
    // Matches semesters_length_check in the migration.
    private static final long MAX_DAYS_BETWEEN_START_AND_END = 365;

    private final SupabaseClient supabase;
    private final ObjectMapper mapper = new ObjectMapper();

    public SemesterController(SupabaseClient supabase) {
        this.supabase = supabase;
    }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("semesters?select=" + PUBLIC_COLUMNS + "&order=start_date.asc"));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        ObjectNode semester = readSemesterFields(body);

        if (!semester.has("name")) {
            throw new IllegalArgumentException("name is required.");
        }
        if (!semester.has("start_date") || !semester.has("end_date")) {
            throw new IllegalArgumentException("start_date and end_date are required.");
        }

        validateDateRange(
            LocalDate.parse(semester.path("start_date").asText()),
            LocalDate.parse(semester.path("end_date").asText())
        );

        return fromSupabase(supabase.postResponse("semesters", semester.toString()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable long id, @RequestBody String body) {
        ObjectNode changes = readSemesterFields(body);

        if (changes.isEmpty()) {
            throw new IllegalArgumentException("No updatable fields provided.");
        }

        JsonNode existing = findSemester(id);

        if (existing == null) {
            return CalendarRuleErrors.jsonError(404, "Semester not found.");
        }

        // A partial update is checked against the dates it will end up with.
        validateDateRange(
            LocalDate.parse(changes.path("start_date").asText(existing.path("start_date").asText())),
            LocalDate.parse(changes.path("end_date").asText(existing.path("end_date").asText()))
        );

        SupabaseClient.SupabaseResponse response =
            supabase.patchResponse("semesters?id=eq." + id, changes.toString());

        if (response.isSuccessful() && isEmptyArray(response.body())) {
            return CalendarRuleErrors.jsonError(404, "Semester not found.");
        }

        return fromSupabase(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (findSemester(id) == null) {
            return CalendarRuleErrors.jsonError(404, "Semester not found.");
        }

        SupabaseClient.SupabaseResponse response = supabase.delete("semesters?id=eq." + id);

        if (!response.isSuccessful()) {
            ResponseEntity<String> ruleError = CalendarRuleErrors.toResponse(response);
            if (ruleError != null) {
                return ruleError;
            }
        }

        return DeleteOutcome.toResponse(
            response,
            "Unavailable days still belong to this semester, so it cannot be deleted."
        );
    }

    private ObjectNode readSemesterFields(String body) {
        JsonNode input;

        try {
            input = mapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalArgumentException("Malformed semester body.");
        }

        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("Semester body must be a JSON object.");
        }

        ObjectNode semester = mapper.createObjectNode();

        if (input.has("name")) {
            JsonNode name = input.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                throw new IllegalArgumentException("name must be non-empty text.");
            }
            if (name.asText().trim().length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("name must be " + MAX_NAME_LENGTH + " characters or fewer.");
            }
            semester.put("name", name.asText().trim());
        }

        copyDate(input, semester, "start_date");
        copyDate(input, semester, "end_date");

        return semester;
    }

    private void copyDate(JsonNode input, ObjectNode semester, String field) {
        if (!input.has(field)) {
            return;
        }

        JsonNode value = input.get(field);
        String message = field + " must be a date in YYYY-MM-DD format.";

        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(message);
        }

        try {
            semester.put(field, LocalDate.parse(value.asText()).toString());
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("start_date must be on or before end_date.");
        }

        if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_DAYS_BETWEEN_START_AND_END) {
            throw new IllegalArgumentException("A semester can be at most one year long.");
        }
    }

    private JsonNode findSemester(long id) {
        try {
            JsonNode rows = mapper.readTree(supabase.get("semesters?id=eq." + id + "&select=" + PUBLIC_COLUMNS));
            return rows.isArray() && !rows.isEmpty() ? rows.get(0) : null;
        } catch (Exception error) {
            throw new IllegalStateException("Could not load semester.");
        }
    }

    private boolean isEmptyArray(String body) {
        try {
            JsonNode rows = mapper.readTree(body);
            return rows.isArray() && rows.isEmpty();
        } catch (Exception error) {
            return false;
        }
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response) {
        if (response.isSuccessful()) {
            return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
        }

        ResponseEntity<String> ruleError = CalendarRuleErrors.toResponse(response);
        if (ruleError != null) {
            return ruleError;
        }

        return switch (response.errorCode()) {
            case CalendarRuleErrors.EXCLUSION_VIOLATION ->
                CalendarRuleErrors.jsonError(409, "This semester overlaps an existing semester.");
            case CalendarRuleErrors.UNIQUE_VIOLATION ->
                CalendarRuleErrors.jsonError(409, "A semester with this name already exists.");
            case CalendarRuleErrors.CHECK_VIOLATION ->
                CalendarRuleErrors.jsonError(400, "The semester name or dates are invalid.");
            default ->
                CalendarRuleErrors.jsonError(502, "The database could not complete this request.");
        };
    }
}
