package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationControllerPrivacyTest {
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService authService = mock(AuthService.class);
    private final ReservationController controller = new ReservationController(
        supabase, new ObjectMapper(), authService
    );
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anonymousResponseRemovesPrivateEventDetailsAndHostIdentity() throws Exception {
        when(supabase.get(anyString())).thenReturn("""
            [{"id":7,"title":"Confidential meeting","description":"Sensitive notes","department":"HR",
              "event_type":"Meeting","is_public":false,"start_time":"2026-10-01T16:00:00Z",
              "end_time":"2026-10-01T17:00:00Z","host_user_id":12,"recurrence_group_id":"series-1",
              "users":{"first_name":"A","last_name":"B"}},
             {"id":8,"title":"Open house","is_public":true}]
            """);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("currentUser")).thenReturn(null);
        when(authService.isFacultyOrAdmin(null)).thenReturn(false);

        ResponseEntity<String> response = controller.getAll(request);
        JsonNode events = mapper.readTree(response.getBody());
        JsonNode privateEvent = events.get(0);

        assertEquals("Private event", privateEvent.path("title").asText());
        assertEquals(false, privateEvent.path("is_public").asBoolean());
        assertEquals("2026-10-01T16:00:00Z", privateEvent.path("start_time").asText());
        assertFalse(privateEvent.has("description"));
        assertFalse(privateEvent.has("department"));
        assertFalse(privateEvent.has("event_type"));
        assertFalse(privateEvent.has("host_user_id"));
        assertFalse(privateEvent.has("users"));
        assertFalse(privateEvent.has("recurrence_group_id"));
        assertEquals("Open house", events.get(1).path("title").asText());
    }

    @Test
    void facultyAndAdminKeepFullEventDetails() throws Exception {
        when(supabase.get(anyString())).thenReturn(
            "[{\"id\":7,\"title\":\"Confidential meeting\",\"is_public\":false}]"
        );
        HttpServletRequest request = mock(HttpServletRequest.class);
        JsonNode faculty = mapper.readTree("{\"role_name\":\"faculty\"}");
        when(request.getAttribute("currentUser")).thenReturn(faculty);
        when(authService.isFacultyOrAdmin(faculty)).thenReturn(true);

        ResponseEntity<String> response = controller.getAll(request);

        assertTrue(response.getBody().contains("Confidential meeting"));
    }
}
