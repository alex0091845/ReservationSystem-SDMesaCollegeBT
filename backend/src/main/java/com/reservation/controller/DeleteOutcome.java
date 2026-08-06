package com.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Shared result handling for DELETE endpoints.
 *
 * <p>PostgREST answers a refused delete with a 4xx and a JSON body. Returning
 * {@code 204 No Content} without inspecting that response tells the client the row
 * is gone when it is still in the table — the UI drops it from view and it
 * reappears on the next load. Every delete endpoint routes its result through here
 * so a refusal actually reaches the caller.
 */
final class DeleteOutcome {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeleteOutcome() {
    }

    /**
     * @param stillReferencedMessage shown when child rows block the delete; should name
     *                               what still references the record, in the caller's terms
     */
    static ResponseEntity<?> toResponse(
        SupabaseClient.SupabaseResponse response,
        String stillReferencedMessage
    ) {
        if (response.isSuccessful()) {
            return ResponseEntity.noContent().build();
        }

        if (response.isForeignKeyViolation()) {
            return jsonError(409, stillReferencedMessage);
        }

        return jsonError(502, "The delete could not be completed. Please try again.");
    }

    private static ResponseEntity<String> jsonError(int status, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("error", message);

        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(error.toString());
    }
}
