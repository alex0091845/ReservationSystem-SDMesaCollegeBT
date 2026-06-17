package com.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservation.services.AuthService;
import com.reservation.services.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("!mock")
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthController(AuthService authService, SessionService sessionService) {
        this.authService = authService;
        this.sessionService = sessionService;
    }

    @PostMapping({"/login", "/auth/login"})
    public ResponseEntity<String> login(@RequestBody String body) {
        try {
            JsonNode credentials = mapper.readTree(body);
            String email = credentials.path("email").asText("");
            String password = credentials.path("password").asText("");
            JsonNode user = authService.authenticate(email, password);

            if (user == null) {
                return ResponseEntity.status(401).body("{\"error\":\"Invalid credentials\"}");
            }

            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionService.createSessionCookie(user).toString())
                .body(user.toString());

        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"Login failed\"}");
        }
    }

    @GetMapping("/session")
    public ResponseEntity<String> getSession(HttpServletRequest request) {
        try {
            JsonNode currentUser = sessionService.getCurrentUser(request);

            if (currentUser == null) {
                return ResponseEntity.status(401).body("{\"error\":\"No active session\"}");
            }

            return ResponseEntity.ok(currentUser.toString());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"Session lookup failed\"}");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(HttpServletRequest request) {
        sessionService.invalidateCurrentSession(request);

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionService.clearSessionCookie().toString())
            .body("{\"success\":true}");
    }
}
