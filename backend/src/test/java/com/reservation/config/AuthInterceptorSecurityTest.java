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

    private static final String[][] ADMIN_ONLY = {
        {"POST", "/api/events/special"},
        {"GET", "/api/admin/closure-reviews"},
        {"GET", "/api/admin/closure-reviews/8"},
        {"POST", "/api/admin/closure-reviews/8/review"},
        {"POST", "/api/admin/closure-reviews/8/follow-up"},
        {"GET", "/api/admin/closure-notifications"},
        {"POST", "/api/admin/closure-notifications/8/retry"},
        {"GET", "/api/admin/special-reservations"},
    };

    @Test
    void facultyCannotReachOverrideOrReviewEndpoints() throws Exception {
        var faculty = new ObjectMapper().readTree("{\"role_name\":\"faculty\"}");
        when(auth.isAdmin(faculty)).thenReturn(false);
        for (String[] route : ADMIN_ONLY) {
            HttpServletRequest request = request(route[0], route[1]);
            when(sessions.getCurrentUser(request)).thenReturn(faculty);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(interceptor.preHandle(request, response, new Object()), route[1]);
            assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus(), route[1]);
        }
    }

    @Test
    void anonymousUsersCannotReachOverrideOrReviewEndpoints() throws Exception {
        for (String[] route : ADMIN_ONLY) {
            HttpServletRequest request = request(route[0], route[1]);
            when(sessions.getCurrentUser(request)).thenReturn(null);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(interceptor.preHandle(request, response, new Object()), route[1]);
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus(), route[1]);
        }
    }

    @Test
    void adminsCanReachOverrideAndReviewEndpoints() throws Exception {
        var admin = new ObjectMapper().readTree("{\"role_name\":\"admin\"}");
        when(auth.isAdmin(admin)).thenReturn(true);
        when(auth.isFacultyOrAdmin(admin)).thenReturn(true);
        for (String[] route : ADMIN_ONLY) {
            HttpServletRequest request = request(route[0], route[1]);
            when(sessions.getCurrentUser(request)).thenReturn(admin);

            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()), route[1]);
        }
    }

    @Test
    void facultyCanStillCreateOrdinaryReservations() throws Exception {
        var faculty = new ObjectMapper().readTree("{\"role_name\":\"faculty\"}");
        HttpServletRequest request = request("POST", "/api/events");
        when(sessions.getCurrentUser(request)).thenReturn(faculty);
        when(auth.isAdmin(faculty)).thenReturn(false);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void onlyAdminsCanChangeReservationAvailability() throws Exception {
        HttpServletRequest request = request("PATCH", "/api/reservation-availability");
        var faculty = new ObjectMapper().readTree("{\"role_name\":\"faculty\"}");
        when(sessions.getCurrentUser(request)).thenReturn(faculty);
        when(auth.isAdmin(faculty)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    void facultyCanReadReservationAvailability() throws Exception {
        HttpServletRequest request = request("GET", "/api/reservation-availability");
        var faculty = new ObjectMapper().readTree("{\"role_name\":\"faculty\"}");
        when(sessions.getCurrentUser(request)).thenReturn(faculty);
        when(auth.isFacultyOrAdmin(faculty)).thenReturn(true);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void anonymousUsersCannotReadReservationAvailability() throws Exception {
        HttpServletRequest request = request("GET", "/api/reservation-availability");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }
}
