package com.reservation.controller;

import com.reservation.services.ReservationAvailabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/reservation-availability")
public class ReservationAvailabilityController {
    private final ReservationAvailabilityService availability;

    public ReservationAvailabilityController(ReservationAvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        try {
            return ResponseEntity.ok(availability.toJson(availability.get()));
        } catch (ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason()));
        }
    }

    @PatchMapping
    public ResponseEntity<?> save(@RequestBody JsonNode input) {
        try {
            return ResponseEntity.ok(availability.toJson(availability.save(input)));
        } catch (ReservationAvailabilityService.CancellationConfirmationRequired error) {
            return ResponseEntity.status(409).body(Map.of("error", error.getMessage(),
                "conflicting_reservations", error.reservations()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        } catch (ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason()));
        }
    }
}
