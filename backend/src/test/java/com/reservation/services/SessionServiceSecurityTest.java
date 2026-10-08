package com.reservation.services;

import tools.jackson.databind.ObjectMapper;
import com.reservation.config.SupabaseClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionServiceSecurityTest {
    @Test
    void databaseStoresOnlyHashOfCookieToken() throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        AuthService auth = new AuthService(supabase, mock(PasswordEncoder.class));
        SessionService sessions = new SessionService(supabase, auth);
        ReflectionTestUtils.setField(sessions, "cookieName", "session_id");
        ReflectionTestUtils.setField(sessions, "cookieSecure", true);
        ReflectionTestUtils.setField(sessions, "cookieSameSite", "Lax");
        ReflectionTestUtils.setField(sessions, "sessionHours", 8L);
        when(supabase.post(eq("sessions"), anyString())).thenReturn("[]");
        var user = new ObjectMapper().readTree("{\"id\":4}");

        var cookie = sessions.createSessionCookie(user);
        var stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(supabase).post(eq("sessions"), stored.capture());
        String storedToken = new ObjectMapper().readTree(stored.getValue()).path("session_id").asText();
        String expectedHash = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(cookie.getValue().getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(expectedHash, storedToken);
        assertNotEquals(cookie.getValue(), storedToken);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Lax", cookie.getSameSite());
        assertEquals(Duration.ofHours(8), cookie.getMaxAge());
    }
}
