package com.reservation.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

@Service
public class SessionService {
    private final SupabaseClient supabase;
    private final AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @Value("${auth.cookie.name:session_id}")
    private String cookieName;

    @Value("${auth.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${auth.cookie.same-site:Lax}")
    private String cookieSameSite;

    @Value("${auth.session.hours:8}")
    private long sessionHours;

    public SessionService(SupabaseClient supabase, AuthService authService) {
        this.supabase = supabase;
        this.authService = authService;
    }

    public ResponseCookie createSessionCookie(JsonNode user) {
        String token = generateSessionToken();
        Instant expiresAt = Instant.now().plus(getSessionDuration());

        ObjectNode sessionData = mapper.createObjectNode();
        sessionData.put("session_id", token);
        sessionData.put("user_id", user.path("id").asInt());
        sessionData.put("expires_at", expiresAt.toString());

        supabase.post("sessions", sessionData.toString());

        return buildCookie(token, getSessionDuration());
    }

    public JsonNode getCurrentUser(HttpServletRequest request) {
        String sessionToken = getSessionToken(request);

        if (sessionToken == null || sessionToken.isBlank()) {
            return null;
        }

        JsonNode session = findSession(sessionToken);

        if (session == null || isInvalidated(session) || isExpired(session)) {
            invalidateSession(sessionToken);
            return null;
        }

        touchSession(sessionToken);

        return authService.findUserById(session.path("user_id").asInt());
    }

    public void invalidateCurrentSession(HttpServletRequest request) {
        String sessionToken = getSessionToken(request);

        if (sessionToken != null && !sessionToken.isBlank()) {
            invalidateSession(sessionToken);
        }
    }

    public ResponseCookie clearSessionCookie() {
        return buildCookie("", Duration.ZERO);
    }

    private JsonNode findSession(String sessionToken) {
        try {
            String response = supabase.get("sessions?session_id=eq." + encode(sessionToken) + "&select=*");
            JsonNode sessions = mapper.readTree(response);

            if (!sessions.isArray() || sessions.isEmpty()) {
                return null;
            }

            return sessions.get(0);
        } catch (Exception e) {
            throw new RuntimeException("Could not read session");
        }
    }

    private void invalidateSession(String sessionToken) {
        ObjectNode invalidationData = mapper.createObjectNode();
        invalidationData.put("invalidated_at", Instant.now().toString());

        supabase.patch("sessions?session_id=eq." + encode(sessionToken), invalidationData.toString());
    }

    private void touchSession(String sessionToken) {
        ObjectNode lastSeenData = mapper.createObjectNode();
        lastSeenData.put("last_seen_at", Instant.now().toString());

        supabase.patch("sessions?session_id=eq." + encode(sessionToken), lastSeenData.toString());
    }

    private boolean isInvalidated(JsonNode session) {
        JsonNode invalidatedAt = session.get("invalidated_at");

        return invalidatedAt != null && !invalidatedAt.isNull() && !invalidatedAt.asText("").isBlank();
    }

    private boolean isExpired(JsonNode session) {
        try {
            return parseInstant(session.path("expires_at").asText()).isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    private String getSessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    private String generateSessionToken() {
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(cookieName, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    private Duration getSessionDuration() {
        return Duration.ofHours(sessionHours);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
