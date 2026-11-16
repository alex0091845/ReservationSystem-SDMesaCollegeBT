package com.reservation.controller;

import tools.jackson.databind.ObjectMapper;
import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import com.reservation.services.ReservationAvailabilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationControllerSeriesDeleteTest {
    private static final String SERIES_LOOKUP =
        "events?recurrence_group_id=eq.group-1&select=id,host_user_id,recurrence_group_id,is_closure";

    private static final String CHECK_IN_LOOKUP = "attendees?event_id=in.(7,8)&select=id,event_id";

    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService authService = mock(AuthService.class);
    private final ReservationController controller = new ReservationController(
        supabase, new ObjectMapper(), authService, mock(ReservationAvailabilityService.class)
    );

    @Test
    void unknownSeriesReturnsNotFoundAndWritesNothing() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(supabase.get(SERIES_LOOKUP)).thenReturn("[]");

        ResponseEntity<?> response = controller.deleteSeries("group-1", request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void facultyWhoDoesNotOwnEveryEventGetsForbiddenAndWritesNothing() throws Exception {
        var faculty = new ObjectMapper().readTree("{\"id\":42,\"role_name\":\"faculty\"}");
        HttpServletRequest request = requestFor(faculty);
        when(authService.isAdmin(faculty)).thenReturn(false);
        when(authService.isFacultyOrAdmin(faculty)).thenReturn(true);
        when(supabase.get(SERIES_LOOKUP)).thenReturn(
            "[{\"id\":7,\"host_user_id\":42},{\"id\":8,\"host_user_id\":99}]"
        );

        ResponseEntity<?> response = controller.deleteSeries("group-1", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void seriesContainingAnUnavailableDateIsRefusedAndWritesNothing() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(authService.isAdmin(admin)).thenReturn(true);
        when(supabase.get(SERIES_LOOKUP)).thenReturn(
            "[{\"id\":7,\"host_user_id\":42},{\"id\":8,\"host_user_id\":42,\"is_closure\":true}]"
        );

        ResponseEntity<?> response = controller.deleteSeries("group-1", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void successDetachesCheckInsThenDeletesEventsWithoutDeletingAttendees() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(authService.isAdmin(admin)).thenReturn(true);
        when(supabase.get(SERIES_LOOKUP)).thenReturn(
            "[{\"id\":7,\"host_user_id\":42},{\"id\":8,\"host_user_id\":42}]"
        );
        when(supabase.get(CHECK_IN_LOOKUP)).thenReturn("[{\"id\":100,\"event_id\":7}]");
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));
        when(supabase.delete(anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(204, ""));

        ResponseEntity<?> response = controller.deleteSeries("group-1", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(supabase);
        order.verify(supabase).patchResponse(
            eq("attendees?event_id=in.(7,8)"), eq("{\"event_id\":null}")
        );
        order.verify(supabase).delete("events?id=in.(7,8)");
        verify(supabase, never()).delete(startsWith("attendees"));
        verify(supabase, times(1)).patchResponse(anyString(), anyString());
    }

    @Test
    void failedEventDeleteRestoresCheckInsToTheirEvents() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(authService.isAdmin(admin)).thenReturn(true);
        when(supabase.get(SERIES_LOOKUP)).thenReturn(
            "[{\"id\":7,\"host_user_id\":42},{\"id\":8,\"host_user_id\":42}]"
        );
        when(supabase.get(CHECK_IN_LOOKUP)).thenReturn(
            "[{\"id\":100,\"event_id\":7},{\"id\":101,\"event_id\":8},{\"id\":102,\"event_id\":7}]"
        );
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));
        when(supabase.delete(anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(500, "{}"));

        ResponseEntity<?> response = controller.deleteSeries("group-1", request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        verify(supabase).patchResponse("attendees?id=in.(100,102)", "{\"event_id\":7}");
        verify(supabase).patchResponse("attendees?id=in.(101)", "{\"event_id\":8}");
    }

    @Test
    void failedDetachReturnsBadGatewayAndDoesNotDeleteEvents() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(authService.isAdmin(admin)).thenReturn(true);
        when(supabase.get(SERIES_LOOKUP)).thenReturn("[{\"id\":7,\"host_user_id\":42}]");
        when(supabase.get("attendees?event_id=in.(7)&select=id,event_id")).thenReturn("[]");
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(500, "{}"));

        ResponseEntity<?> response = controller.deleteSeries("group-1", request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        verify(supabase, never()).delete(anyString());
    }

    @Test
    void groupIdIsUrlEncodedInTheLookup() throws Exception {
        var admin = new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\"}");
        HttpServletRequest request = requestFor(admin);
        when(supabase.get(anyString())).thenReturn("[]");

        controller.deleteSeries("a&b,(c) d", request);

        verify(supabase).get(
            "events?recurrence_group_id=eq.a%26b%2C%28c%29+d&select=id,host_user_id,recurrence_group_id,is_closure"
        );
    }

    private void verifyNoWrites() {
        verify(supabase, never()).delete(anyString());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    private HttpServletRequest requestFor(Object user) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("currentUser")).thenReturn(user);
        return request;
    }
}
