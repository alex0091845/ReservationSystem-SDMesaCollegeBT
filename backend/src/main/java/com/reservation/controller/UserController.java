package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
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
        return ResponseEntity.ok(sanitizeUserResponse(
            supabase.get("users?select=id,email,first_name,last_name,phone,role_name,enabled,user_roles(name)")
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(sanitizeUserResponse(supabase.get(
            "users?id=eq." + id + "&select=id,email,first_name,last_name,phone,role_name,enabled,user_roles(name)"
        )));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        try {
            String payload = buildUserPayload(body, true);
            return fromSupabase(supabase.postResponse("users", payload));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        try {
            String payload = buildUserPayload(body, false);
            return fromSupabase(supabase.patchResponse("users?id=eq." + id, payload));
        } catch (IllegalArgumentException error) {
            return jsonError(400, error.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        return DeleteOutcome.toResponse(
            supabase.delete("users?id=eq." + id),
            "This user still has reservations or sessions on record, so the account cannot be deleted."
        );
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

        if (payload.has("email")) {
            String email = payload.path("email").asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (email.isBlank() || email.length() > 254) {
                throw new IllegalArgumentException("email must be between 1 and 254 characters");
            }
            payload.put("email", email);
        }

        if (input.hasNonNull("enabled")) {
            payload.put("enabled", input.path("enabled").asBoolean());
        } else if (isCreate) {
            payload.put("enabled", true);
        }

        String password = input.path("password").asText("");
        if (!password.isEmpty()) {
            int passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (passwordBytes < 12 || passwordBytes > 72) {
                throw new IllegalArgumentException("password must be between 12 and 72 UTF-8 bytes");
            }
            payload.put("password_hash", encoder.encode(password));
        }

        if (isCreate) {
            if (!payload.has("email")) {
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

        node.forEach(this::removePasswordHashes);
    }

    private ResponseEntity<String> fromSupabase(SupabaseClient.SupabaseResponse response) {
        if (!response.isSuccessful()) {
            return jsonError(502, "The database could not complete this request.");
        }
        return ResponseEntity.status(response.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(sanitizeUserResponse(response.body()));
    }

    private ResponseEntity<String> jsonError(int status, String message) {
        ObjectNode error = mapper.createObjectNode();
        error.put("error", message);

        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(error.toString());
    }
}
