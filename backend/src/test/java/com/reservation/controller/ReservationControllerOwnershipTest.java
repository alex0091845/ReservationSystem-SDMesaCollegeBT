package com.reservation.controller;

import tools.jackson.databind.ObjectMapper;
import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationControllerOwnershipTest {
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService authService = mock(AuthService.class);
    private final ReservationController controller = new ReservationController(
        supabase, new ObjectMapper(), authService
    );

    @Test
    void facultyOwnerCanChangeEventTypeAndOtherEventData() throws Exception {
        var faculty = new ObjectMapper().readTree("{\"id\":42,\"role_name\":\"faculty\"}");
        HttpServletRequest request = requestFor(faculty);
        when(authService.isAdmin(faculty)).thenReturn(false);
        when(authService.isFacultyOrAdmin(faculty)).thenReturn(true);
        stubExistingEvent(42);
        when(supabase.patchResponse(eq("events?id=eq.7"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));

        ResponseEntity<String> response = controller.update(7, eventBody(42, "Workshop"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(supabase).patchResponse(eq("events?id=eq.7"), argThat(body -> body.contains("Workshop")));
    }

    @Test
    void facultyCannotChangeAnotherFacultyMembersEvent() throws Exception {
        var faculty = new ObjectMapper().readTree("{\"id\":42,\"role_name\":\"faculty\"}");
        HttpServletRequest request = requestFor(faculty);
        when(authService.isAdmin(faculty)).thenReturn(false);
        when(authService.isFacultyOrAdmin(faculty)).thenReturn(true);
        stubExistingEvent(99);

        ResponseEntity<String> response = controller.update(7, eventBody(42, "Workshop"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void nonFacultyOwnerCannotChangeEvent() throws Exception {
        var student = new ObjectMapper().readTree("{\"id\":42,\"role_name\":\"student\"}");
        HttpServletRequest request = requestFor(student);
        when(authService.isAdmin(student)).thenReturn(false);
        when(authService.isFacultyOrAdmin(student)).thenReturn(false);
        stubExistingEvent(42);

        ResponseEntity<String> response = controller.update(7, eventBody(42, "Workshop"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void adminCanChangeAnotherUsersEvent() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(authService.isAdmin(admin)).thenReturn(true);
        stubExistingEvent(99);
        when(supabase.patchResponse(eq("events?id=eq.7"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));

        ResponseEntity<String> response = controller.update(7, eventBody(99, "Workshop"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(supabase).patchResponse(eq("events?id=eq.7"), anyString());
    }

    private HttpServletRequest requestFor(Object user) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("currentUser")).thenReturn(user);
        return request;
    }

    private void stubExistingEvent(long hostUserId) {
        when(supabase.get(contains("select=id,host_user_id"))).thenReturn(
            "[{\"id\":7,\"host_user_id\":" + hostUserId + "}]"
        );
    }

    private String eventBody(long hostUserId, String eventType) {
        return """
            {"host_user_id":%d,"start_time":"2099-07-14T16:00:00Z",
             "end_time":"2099-07-14T17:00:00Z","event_type":"%s",
             "description":"Updated event","title":"Updated title",
             "department":"Math","is_public":true}
            """.formatted(hostUserId, eventType);
    }
}
