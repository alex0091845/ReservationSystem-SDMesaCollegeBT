package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Whole days on which BT-216 cannot be booked (holidays, closures). Each one is a
 * Los Angeles calendar date inside a semester.
 *
 * <p>The list is public and returns only calendar-safe columns. Writes and the
 * affected-reservations list are admin-only, enforced by {@code AuthInterceptor}. The
 * database refuses closures that fall outside a semester, land in the past, or duplicate a
 * date, and refuses to close a day that already has reservations unless the admin explicitly
 * overrides (an emergency or maintenance closure, see {@link CalendarOverrides}). Those
 * reservations are kept and the override is written to {@code calendar_override_audit}.
 * This controller validates input and translates the database's refusals
 * (see {@link CalendarRuleErrors}).
 */
@RestController
@RequestMapping("/api/unavailable-days")
public class UnavailableDayController {

    private static final ZoneId CALENDAR_TIME_ZONE = ZoneId.of("America/Los_Angeles");
    // created_by stays out of public reads.
    private static final String PUBLIC_COLUMNS = "id,semester_id,unavailable_date,reason";
    // Admin-only, so private reservations are listed in full, with the host's contact details.
    private static final String AFFECTED_RESERVATION_COLUMNS =
        "id,title,start_time,end_time,is_public,host_user_id,users(first_name,last_name,email)";
    private static final int MAX_REASON_LENGTH = 200;

    private final SupabaseClient supabase;
    private final ObjectMapper mapper = new ObjectMapper();

    public UnavailableDayController(SupabaseClient supabase) {
        this.supabase = supabase;
    }

    @GetMapping
    public ResponseEntity<String> getAll(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(name = "semester_id", required = false) String semesterId
    ) {
        StringBuilder query = new StringBuilder("unavailable_days?select=" + PUBLIC_COLUMNS);
        LocalDate fromDate = from == null ? null : parseDate(from, "from");
        LocalDate toDate = to == null ? null : parseDate(to, "to");

        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("from must be on or before to.");
        }
        if (fromDate != null) {
            query.append("&unavailable_date=gte.").append(fromDate);
        }
        if (toDate != null) {
            query.append("&unavailable_date=lte.").append(toDate);
        }
        if (semesterId != null) {
            query.append("&semester_id=eq.").append(parseId(semesterId, "semester_id"));
        }

        query.append("&order=unavailable_date.asc");

        return ResponseEntity.ok(supabase.get(query.toString()));
    }

    // The reservations currently on a closed day, worked out from the events table on each
    // call, so a reservation that was moved or deleted no longer appears.
    @GetMapping("/{id}/affected-reservations")
    public ResponseEntity<String> getAffectedReservations(@PathVariable long id) {
        JsonNode closure = findClosure(id);

        if (closure == null) {
            return CalendarRuleErrors.jsonError(404, "Unavailable day not found.");
        }

        // The whole Los Angeles day, which is 23 or 25 hours long on DST change days.
        LocalDate date = LocalDate.parse(closure.path("unavailable_date").asText());
        Instant dayStart = date.atStartOfDay(CALENDAR_TIME_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(CALENDAR_TIME_ZONE).toInstant();

        return ResponseEntity.ok(supabase.get(
            "events?start_time=lt." + dayEnd + "&end_time=gt." + dayStart +
                "&select=" + AFFECTED_RESERVATION_COLUMNS + "&order=start_time.asc,id.asc"
        ));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest request) {
        JsonNode input = readObject(body);

        if (!input.hasNonNull("unavailable_date")) {
            throw new IllegalArgumentException("unavailable_date is required.");
        }

        ObjectNode closure = readClosureFields(input);
        requireNotPast(LocalDate.parse(closure.path("unavailable_date").asText()));
        String overrideReason = CalendarOverrides.reason(input);

        // The author comes from the session, never from the request body.
        JsonNode currentUser = currentUser(request);
        if (currentUser != null && currentUser.path("id").canConvertToInt()) {
            closure.put("created_by", currentUser.path("id").asInt());
        }

        if (overrideReason == null) {
            return fromSupabase(supabase.postResponse("unavailable_days", closure.toString()));
        }

        if (currentUser == null) {
            return CalendarRuleErrors.jsonError(401, "Authentication required");
        }

        // Emergency or maintenance closure of a day that may already have bookings.
        // The bookings are kept; the database writes the override to its audit table.
        ObjectNode call = CalendarOverrides.rpcCall(mapper, currentUser, overrideReason);
        call.set("p_day", closure);

        return fromSupabase(
            supabase.postResponse("rpc/create_unavailable_day_with_override", call.toString()),
            201
        );
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(
        @PathVariable long id,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        JsonNode input = readObject(body);
        ObjectNode changes = readClosureFields(input);
        String overrideReason = CalendarOverrides.reason(input);

        if (changes.isEmpty()) {
            throw new IllegalArgumentException("No updatable fields provided.");
        }

        JsonNode existing = findClosure(id);

        if (existing == null) {
            return CalendarRuleErrors.jsonError(404, "Unavailable day not found.");
        }

        // Editing the reason on a closure that has already passed stays allowed;
        // moving any closure onto a past date does not.
        if (changes.has("unavailable_date") &&
            !changes.path("unavailable_date").asText().equals(existing.path("unavailable_date").asText())) {
            requireNotPast(LocalDate.parse(changes.path("unavailable_date").asText()));
        }

        SupabaseClient.SupabaseResponse response;

        if (overrideReason == null) {
            response = supabase.patchResponse("unavailable_days?id=eq." + id, changes.toString());
        } else {
            JsonNode currentUser = currentUser(request);

            if (currentUser == null) {
                return CalendarRuleErrors.jsonError(401, "Authentication required");
            }

            ObjectNode call = CalendarOverrides.rpcCall(mapper, currentUser, overrideReason);
            call.put("p_unavailable_day_id", id);
            call.set("p_changes", changes);
            response = supabase.postResponse("rpc/update_unavailable_day_with_override", call.toString());
        }

        if (response.isSuccessful() && isEmptyArray(response.body())) {
            return CalendarRuleErrors.jsonError(404, "Unavailable day not found.");
        }

        return fromSupabase(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (findClosure(id) == null) {
            return CalendarRuleErrors.jsonError(404, "Unavailable day not found.");
        }

        return DeleteOutcome.toResponse(
            supabase.delete("unavailable_days?id=eq." + id),
            "This unavailable day is still referenced by other records, so it cannot be deleted."
        );
    }

    private JsonNode readObject(String body) {
        JsonNode input;

        try {
            input = mapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalArgumentException("Malformed unavailable day body.");
        }

        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("Unavailable day body must be a JSON object.");
        }

        return input;
    }

    // Whitelists the writable columns. semester_id is optional: the database
    // fills it in from the date, and rejects one that does not contain the date.
    private ObjectNode readClosureFields(JsonNode input) {
        ObjectNode closure = mapper.createObjectNode();

        if (input.has("unavailable_date")) {
            JsonNode date = input.get("unavailable_date");
            if (date == null || !date.isTextual()) {
                throw new IllegalArgumentException("unavailable_date must be a date in YYYY-MM-DD format.");
            }
            closure.put("unavailable_date", parseDate(date.asText(), "unavailable_date").toString());
        }

        if (input.has("reason")) {
            JsonNode reason = input.get("reason");
            if (reason == null || reason.isNull() || (reason.isTextual() && reason.asText().isBlank())) {
                closure.putNull("reason");
            } else if (!reason.isTextual()) {
                throw new IllegalArgumentException("reason must be text.");
            } else if (reason.asText().trim().length() > MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("reason must be " + MAX_REASON_LENGTH + " characters or fewer.");
            } else {
                closure.put("reason", reason.asText().trim());
            }
        }

        if (input.has("semester_id")) {
            JsonNode semesterId = input.get("semester_id");
            if (semesterId == null || !semesterId.canConvertToLong() || !semesterId.isIntegralNumber()) {
                throw new IllegalArgumentException("semester_id must be a whole number.");
            }
            closure.put("semester_id", semesterId.asLong());
        }

        return closure;
    }

    private void requireNotPast(LocalDate date) {
        if (date.isBefore(LocalDate.now(CALENDAR_TIME_ZONE))) {
            throw new IllegalArgumentException("Unavailable days cannot be set in the past.");
        }
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be a date in YYYY-MM-DD format.");
        }
    }

    private long parseId(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be a whole number.");
        }
    }

    private JsonNode findClosure(long id) {
        try {
            JsonNode rows = mapper.readTree(
                supabase.get("unavailable_days?id=eq." + id + "&select=" + PUBLIC_COLUMNS)
            );
            return rows.isArray() && !rows.isEmpty() ? rows.get(0) : null;
        } catch (Exception error) {
            throw new IllegalStateException("Could not load unavailable day.");
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

    private JsonNode currentUser(HttpServletRequest request) {
        Object currentUser = request.getAttribute("currentUser");

        return currentUser instanceof JsonNode user ? user : null;
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response) {
        return fromSupabase(response, response.statusCode());
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response, int successStatus) {
        if (response.isSuccessful()) {
            return ResponseEntity.status(successStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
        }

        ResponseEntity<String> ruleError = CalendarRuleErrors.toResponse(response);
        if (ruleError != null) {
            return ruleError;
        }

        return switch (response.errorCode()) {
            case CalendarRuleErrors.UNIQUE_VIOLATION ->
                CalendarRuleErrors.jsonError(409, "This date is already marked unavailable.");
            case CalendarRuleErrors.CHECK_VIOLATION ->
                CalendarRuleErrors.jsonError(400, "The unavailable day is invalid.");
            default ->
                CalendarRuleErrors.jsonError(502, "The database could not complete this request.");
        };
    }
}
