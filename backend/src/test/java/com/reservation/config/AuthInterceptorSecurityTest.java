package com.reservation.config;

import tools.jackson.databind.ObjectMapper;
import com.reservation.services.AuthService;
import com.reservation.services.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorSecurityTest {
    private final SessionService sessions = mock(SessionService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(
        sessions, auth, new String[] {"https://dsowhvg574z7c.cloudfront.net"}
    );

    @Test
    void attendeePiiRequiresAStaffSession() throws Exception {
        HttpServletRequest request = request("GET", "/api/attendees");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void authenticatedNonStaffCannotReadAttendeePii() throws Exception {
        HttpServletRequest request = request("GET", "/api/attendees");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var student = new ObjectMapper().readTree("{\"role_name\":\"student\"}");
        when(sessions.getCurrentUser(request)).thenReturn(student);
        when(auth.isFacultyOrAdmin(student)).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    void crossOriginStateChangingRequestIsRejected() throws Exception {
        HttpServletRequest request = request("POST", "/api/login");
        when(request.getHeader("Origin")).thenReturn("https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    private HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }
}
