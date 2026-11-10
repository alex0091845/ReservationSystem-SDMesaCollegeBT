package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a refusal from the special reservation and closure review functions into a response.
 * The messages are written here, so database text never reaches a client.
 */
final class ReservationRuleErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReservationRuleErrors() {
    }

    static ResponseEntity<String> toResponse(SupabaseClient.SupabaseResponse response) {
        String code = "";

        try {
            code = MAPPER.readTree(response.body()).path("code").asText();
        } catch (Exception ignored) {
            // Keep unexpected database responses private
        }

        return switch (code) {
            case "BTA01" -> jsonError(403, "Admin access required");
            case "BTN01" -> jsonError(404, "Review not found.");
            case "BTN02" -> jsonError(409, "Mark the review complete before sending a follow-up.");
            case "BTS01" -> jsonError(409, "This date is not unavailable. Create a normal reservation instead.");
            case "BTS02" -> jsonError(409, "An override does not extend past the last bookable date.");
            case "23P01" -> jsonError(409, "This time overlaps an existing reservation. Choose a different time.");
            case "40P01", "40001" -> jsonError(409, "Another reservation changed while saving. Please try again.");
            case "23503" -> jsonError(400, "The host or event type does not exist.");
            case "22023" -> jsonError(400, "The request is not valid.");
            default -> jsonError(502, "The database could not complete this request.");
        };
    }

    static ResponseEntity<String> jsonError(int status, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("error", message);

        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(error.toString());
    }
}
