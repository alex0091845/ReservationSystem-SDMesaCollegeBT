package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final SupabaseClient supabase;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserController(SupabaseClient supabase, PasswordEncoder encoder) {
        this.supabase = supabase;
        this.encoder = encoder;
    }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(sanitizeUserResponse(supabase.get("users?select=*,user_roles(name)")));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(sanitizeUserResponse(supabase.get("users?id=eq." + id + "&select=*,user_roles(name)")));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        String payload = buildUserPayload(body, true);
        return ResponseEntity.ok(sanitizeUserResponse(supabase.post("users", payload)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        String payload = buildUserPayload(body, false);
        return ResponseEntity.ok(sanitizeUserResponse(supabase.patch("users?id=eq." + id, payload)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        supabase.delete("users?id=eq." + id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Whitelist the columns a client may set on the {@code users} table and hash any
     * supplied password server-side. Raw {@code password_hash} from the client is never
     * trusted; only a {@code password} field is accepted and BCrypt-encoded here.
     */
    private String buildUserPayload(String body, boolean isCreate) {
        JsonNode input;
        try {
            input = mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed request body");
        }

        ObjectNode payload = mapper.createObjectNode();

        copyTextIfPresent(input, payload, "email");
        copyTextIfPresent(input, payload, "first_name");
        copyTextIfPresent(input, payload, "last_name");
        copyTextIfPresent(input, payload, "phone");
        copyTextIfPresent(input, payload, "role_name");

        if (input.hasNonNull("enabled")) {
            payload.put("enabled", input.path("enabled").asBoolean());
        }

        String password = input.path("password").asText("");
        if (!password.isBlank()) {
            payload.put("password_hash", encoder.encode(password));
        }

        if (isCreate) {
            if (payload.path("email").asText("").isBlank()) {
                throw new IllegalArgumentException("email is required");
            }
            if (!payload.has("password_hash")) {
                throw new IllegalArgumentException("password is required");
            }
        }

        if (payload.isEmpty()) {
            throw new IllegalArgumentException("No updatable fields provided");
        }

        return payload.toString();
    }

    private void copyTextIfPresent(JsonNode input, ObjectNode payload, String field) {
        if (input.hasNonNull(field)) {
            payload.put(field, input.path(field).asText());
        }
    }

    private String sanitizeUserResponse(String response) {
        try {
            JsonNode root = mapper.readTree(response);
            removePasswordHashes(root);

            return root.toString();
        } catch (Exception e) {
            return response;
        }
    }

    private void removePasswordHashes(JsonNode node) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            ((ObjectNode) node).remove("password_hash");
        }

        node.elements().forEachRemaining(this::removePasswordHashes);
    }
}
