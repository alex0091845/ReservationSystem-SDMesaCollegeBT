package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SupabaseClient supabase;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthController(SupabaseClient supabase) { this.supabase = supabase; }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody String body) {
        try {
            JsonNode credentials = mapper.readTree(body);
            String email = credentials.get("email").asText();
            String password = credentials.get("password").asText();

            String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
            String response = supabase.get("users?email=eq." + encodedEmail + "&select=*,user_roles(name)");
            JsonNode users = mapper.readTree(response);

            if (users.isEmpty()) {
                return ResponseEntity.status(401).body("{\"error\":\"Invalid credentials\"}");
            }

            JsonNode user = users.get(0);
            String storedHash = user.get("password_hash").asText();

            if (!encoder.matches(password, storedHash)) {
                return ResponseEntity.status(401).body("{\"error\":\"Invalid credentials\"}");
            }

            ((ObjectNode) user).remove("password_hash");
            return ResponseEntity.ok(user.toString());

        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"Login failed\"}");
        }
    }
}
