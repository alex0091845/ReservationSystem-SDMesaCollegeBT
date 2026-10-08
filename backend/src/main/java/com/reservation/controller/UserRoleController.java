package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/roles")
public class UserRoleController {

    private final SupabaseClient supabase;
    private final ObjectMapper mapper = new ObjectMapper();
    public UserRoleController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("user_roles?select=*"));
    }

    @GetMapping("/{roleName}")
    public ResponseEntity<String> getByName(@PathVariable String roleName) {
        return ResponseEntity.ok(supabase.get("user_roles?name=eq." + encode(roleName)));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return fromSupabase(supabase.postResponse("user_roles", buildPayload(body, true)));
    }

    @PatchMapping("/{roleName}")
    public ResponseEntity<String> update(@PathVariable String roleName, @RequestBody String body) {
        return fromSupabase(supabase.patchResponse(
            "user_roles?name=eq." + encode(roleName), buildPayload(body, false)
        ));
    }

    @DeleteMapping("/{roleName}")
    public ResponseEntity<?> delete(@PathVariable String roleName) {
        return DeleteOutcome.toResponse(
            supabase.delete("user_roles?name=eq." + encode(roleName)),
            "This role is still assigned to at least one user, so it cannot be deleted."
        );
    }

    private String buildPayload(String body, boolean isCreate) {
        try {
            JsonNode input = mapper.readTree(body);
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Role body must be a JSON object");
            }
            ObjectNode payload = mapper.createObjectNode();
            copyText(input, payload, "name");
            copyText(input, payload, "description");
            if (isCreate && payload.path("name").asText("").isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
            if (payload.isEmpty()) {
                throw new IllegalArgumentException("No updatable fields provided");
            }
            return payload.toString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Malformed role body");
        }
    }

    private void copyText(JsonNode input, ObjectNode payload, String field) {
        if (input.hasNonNull(field) && input.get(field).isTextual()) {
            payload.put(field, input.path(field).asText());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response) {
        if (!response.isSuccessful()) {
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"The database could not complete this request.\"}");
        }
        return ResponseEntity.status(response.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(response.body());
    }
}
