package com.reservation.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts uncaught exceptions into generic JSON error responses so that internal
 * detail (Supabase host/URL, stack traces, transport errors) never reaches clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return json(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception e) {
        // Deliberately generic: do not surface e.getMessage() to the client.
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<String> json(HttpStatus status, String message) {
        String safeMessage = message == null ? "" : message.replace("\"", "'");
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"" + safeMessage + "\"}");
    }
}
