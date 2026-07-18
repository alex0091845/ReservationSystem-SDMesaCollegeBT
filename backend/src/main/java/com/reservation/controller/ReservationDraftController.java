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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@RestController
@RequestMapping("/api/reservation-drafts")
public class ReservationDraftController {

    private static final String CREATE_DRAFT_TYPE = "create";
    private static final String EDIT_DRAFT_TYPE = "edit";

    private final SupabaseClient supabase;
    private final ObjectMapper objectMapper;
    private final AuthService authService;

    public ReservationDraftController(SupabaseClient supabase, ObjectMapper objectMapper, AuthService authService) {
        this.supabase = supabase;
        this.objectMapper = objectMapper;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<String> getCurrentUserDrafts(
        @RequestParam(name = "draft_type", required = false) String draftType,
        @RequestParam(name = "source_event_id", required = false) Long sourceEventId,
        HttpServletRequest request
    ) {
        JsonNode currentUser = getCurrentUser(request);

        if (currentUser == null) {
            return jsonError(401, "Authentication required.");
        }

        StringBuilder query = new StringBuilder("reservation_drafts?select=*&discarded_at=is.null");
        query.append("&user_id=eq.").append(currentUser.path("id").asLong());

        if (draftType != null && !draftType.isBlank()) {
            String normalizedDraftType = normalizeDraftType(draftType);
            query.append("&draft_type=eq.").append(encode(normalizedDraftType));

            if (CREATE_DRAFT_TYPE.equals(normalizedDraftType)) {
                query.append("&source_event_id=is.null");
            }
        }

        if (sourceEventId != null) {
            query.append("&source_event_id=eq.").append(sourceEventId);
        }

        query.append("&order=updated_at.desc");

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(supabase.get(query.toString()));
    }

    @PostMapping
    public ResponseEntity<String> saveDraft(@RequestBody String body, HttpServletRequest request) {
        JsonNode currentUser = getCurrentUser(request);

        if (currentUser == null) {
            return jsonError(401, "Authentication required.");
        }

        try {
            ObjectNode draftBody = normalizeDraftBody(body, currentUser);
            String draftType = draftBody.path("draft_type").asText();
            JsonNode sourceEventId = draftBody.get("source_event_id");

            if (
                EDIT_DRAFT_TYPE.equals(draftType) &&
                !canDraftExistingEvent(currentUser, sourceEventId.asLong())
            ) {
                return jsonError(403, "You do not have permission to save a draft for this reservation.");
            }

            JsonNode existingDraft = getExistingDraft(
                currentUser.path("id").asLong(),
                draftType,
                sourceEventId == null || sourceEventId.isNull() ? null : sourceEventId.asLong()
            );

            if (existingDraft != null) {
                return fromSupabase(supabase.patchResponse(
                    "reservation_drafts?id=eq." + existingDraft.path("id").asLong(),
                    objectMapper.writeValueAsString(draftBody)
                ));
            }

            return fromSupabase(supabase.postResponse(
                "reservation_drafts",
                objectMapper.writeValueAsString(draftBody)
            ));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDraft(@PathVariable long id, HttpServletRequest request) {
        JsonNode currentUser = getCurrentUser(request);

        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }

        ObjectNode discardBody = objectMapper.createObjectNode();
        discardBody.put("discarded_at", Instant.now().toString());
        discardBody.put("updated_at", Instant.now().toString());

        supabase.patch(
            "reservation_drafts?id=eq." + id + "&user_id=eq." + currentUser.path("id").asLong(),
            discardBody.toString()
        );

        return ResponseEntity.noContent().build();
    }

    private ObjectNode normalizeDraftBody(String body, JsonNode currentUser) {
        JsonNode input = parseInput(body);
        String draftType = normalizeDraftType(input.path("draft_type").asText(""));
        ObjectNode draft = objectMapper.createObjectNode();

        draft.put("user_id", currentUser.path("id").asLong());
        draft.put("draft_type", draftType);
        draft.put("updated_at", Instant.now().toString());
        draft.putNull("discarded_at");

        Long hostUserId = parseOptionalLong(input.get("host_user_id"), "host_user_id");

        if (hostUserId != null) {
            draft.put("host_user_id", hostUserId);
        }

        if (EDIT_DRAFT_TYPE.equals(draftType)) {
            Long sourceEventId = parseOptionalLong(input.get("source_event_id"), "source_event_id");

            if (sourceEventId == null) {
                throw new IllegalArgumentException("source_event_id is required for edit drafts.");
            }

            draft.put("source_event_id", sourceEventId);
        } else {
            draft.putNull("source_event_id");
        }

        JsonNode payload = input.get("payload");

        if (payload == null || payload.isNull() || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object.");
        }

        draft.set("payload", payload);

        return draft;
    }

    private Long parseOptionalLong(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            return null;
        }

        if (value.isTextual() && value.asText().isBlank()) {
            return null;
        }

        if (value.canConvertToLong()) {
            return value.asLong();
        }

        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(fieldName + " must be a number.");
            }
        }

        throw new IllegalArgumentException(fieldName + " must be a number.");
    }

    private JsonNode parseInput(String body) {
        try {
            JsonNode input = objectMapper.readTree(body);

            if (!input.isObject()) {
                throw new IllegalArgumentException("Draft body must be a JSON object.");
            }

            return input;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Draft body could not be parsed.");
        }
    }

    private String normalizeDraftType(String value) {
        String normalizedValue = value == null ? "" : value.trim().toLowerCase();

        if (!CREATE_DRAFT_TYPE.equals(normalizedValue) && !EDIT_DRAFT_TYPE.equals(normalizedValue)) {
            throw new IllegalArgumentException("draft_type must be create or edit.");
        }

        return normalizedValue;
    }

    private JsonNode getExistingDraft(long userId, String draftType, Long sourceEventId) {
        try {
            StringBuilder query = new StringBuilder("reservation_drafts?select=*&discarded_at=is.null");

            query.append("&user_id=eq.").append(userId);
            query.append("&draft_type=eq.").append(encode(draftType));

            if (sourceEventId == null) {
                query.append("&source_event_id=is.null");
            } else {
                query.append("&source_event_id=eq.").append(sourceEventId);
            }

            query.append("&limit=1");

            JsonNode drafts = objectMapper.readTree(supabase.get(query.toString()));

            if (!drafts.isArray() || drafts.isEmpty()) {
                return null;
            }

            return drafts.get(0);
        } catch (Exception error) {
            throw new RuntimeException("Could not read reservation draft.");
        }
    }

    private boolean canDraftExistingEvent(JsonNode currentUser, long sourceEventId) {
        JsonNode event = getExistingEvent(sourceEventId);

        if (event == null) {
            return false;
        }

        if (authService.isAdmin(currentUser)) {
            return true;
        }

        return currentUser.path("id").asLong() == event.path("host_user_id").asLong();
    }

    private JsonNode getExistingEvent(long id) {
        try {
            JsonNode events = objectMapper.readTree(
                supabase.get("events?id=eq." + id + "&select=id,host_user_id")
            );

            if (!events.isArray() || events.isEmpty()) {
                return null;
            }

            return events.get(0);
        } catch (Exception error) {
            throw new RuntimeException("Could not verify reservation ownership.");
        }
    }

    private JsonNode getCurrentUser(HttpServletRequest request) {
        Object currentUser = request.getAttribute("currentUser");

        return currentUser instanceof JsonNode ? (JsonNode) currentUser : null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
