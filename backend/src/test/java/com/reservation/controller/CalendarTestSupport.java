package com.reservation.controller;

import com.reservation.config.GlobalExceptionHandler;
import com.reservation.config.SupabaseClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared helpers for the calendar controller tests. Controllers are called directly
 * (house style); exceptions they throw are translated exactly the way
 * {@link GlobalExceptionHandler} does at runtime, so assertions see the HTTP status a
 * client would see.
 */
final class CalendarTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private static final GlobalExceptionHandler HANDLER = new GlobalExceptionHandler();

    private CalendarTestSupport() {
    }

    static ResponseEntity<?> run(Supplier<? extends ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException error) {
            return HANDLER.handleBadRequest(error);
        } catch (RuntimeException error) {
            return HANDLER.handleUnexpected(error);
        }
    }

    static JsonNode json(ResponseEntity<?> response) {
        Object body = response.getBody();
        return MAPPER.readTree(body == null ? "null" : body.toString());
    }

    static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    static LocalDate todayLA() {
        return LocalDate.now(LA);
    }

    /** A PostgREST error body as produced for a RAISE with a JSON DETAIL. */
    static SupabaseClient.SupabaseResponse dbError(int status, String code, JsonNode details, String message) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("code", code);
        if (details == null) {
            body.putNull("details");
        } else {
            body.put("details", details.toString());
        }
        body.putNull("hint");
        body.put("message", message);
        return new SupabaseClient.SupabaseResponse(status, body.toString());
    }

    static SupabaseClient.SupabaseResponse dbError(int status, String code, String plainDetails, String message) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("code", code);
        body.put("details", plainDetails);
        body.putNull("hint");
        body.put("message", message);
        return new SupabaseClient.SupabaseResponse(status, body.toString());
    }

    static SupabaseClient.SupabaseResponse ok(int status, String body) {
        return new SupabaseClient.SupabaseResponse(status, body);
    }

    static JsonNode user(int id, String role) {
        return MAPPER.readTree("{\"id\":" + id + ",\"email\":\"u" + id + "@sdccd.edu\",\"role_name\":\"" + role + "\"}");
    }

    static HttpServletRequest requestFor(Object currentUser) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("currentUser")).thenReturn(currentUser);
        return request;
    }
}
