package com.reservation.services;

import com.reservation.config.SupabaseClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceSecurityTest {
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthService authService = new AuthService(supabase, encoder);

    @Test
    void disabledAccountCannotAuthenticateEvenWithCorrectPassword() {
        when(supabase.get(contains("email=eq.person%40example.com"))).thenReturn(
            "[{\"id\":3,\"email\":\"person@example.com\",\"enabled\":false,\"password_hash\":\"$2b$hash\"}]"
        );
        when(encoder.matches("correct-password", "$2b$hash")).thenReturn(true);

        assertNull(authService.authenticate(" Person@Example.com ", "correct-password"));
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void enabledAccountAuthenticatesAndPasswordHashIsNotReturned() {
        when(supabase.get(contains("email=eq.person%40example.com"))).thenReturn(
            "[{\"id\":3,\"email\":\"person@example.com\",\"enabled\":true,\"role_name\":\"faculty\",\"password_hash\":\"$2b$hash\"}]"
        );
        when(encoder.matches("correct-password", "$2b$hash")).thenReturn(true);

        var user = authService.authenticate(" Person@Example.com ", "correct-password");

        assertNotNull(user);
        assertEquals("faculty", user.path("role_name").asText());
        assertFalse(user.has("password_hash"));
    }

    @Test
    void facultyAndAdminAreRecognizedAsStaff() throws Exception {
        var faculty = new tools.jackson.databind.ObjectMapper().readTree("{\"role_name\":\"faculty\"}");
        var admin = new tools.jackson.databind.ObjectMapper().readTree("{\"role_name\":\"admin\"}");
        var student = new tools.jackson.databind.ObjectMapper().readTree("{\"role_name\":\"student\"}");

        assertTrue(authService.isFacultyOrAdmin(faculty));
        assertTrue(authService.isFacultyOrAdmin(admin));
        assertFalse(authService.isFacultyOrAdmin(student));
    }
}
