package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("!mock")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final SupabaseClient supabase;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserController(SupabaseClient supabase) { this.supabase = supabase; }

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
        return ResponseEntity.ok(sanitizeUserResponse(supabase.post("users", body)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        return ResponseEntity.ok(sanitizeUserResponse(supabase.patch("users?id=eq." + id, body)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        supabase.delete("users?id=eq." + id);
        return ResponseEntity.noContent().build();
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
