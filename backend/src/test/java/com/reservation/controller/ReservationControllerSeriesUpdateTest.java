package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import com.reservation.services.ReservationAvailabilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationControllerSeriesUpdateTest {
    private static final String SERIES_LOOKUP =
        "events?recurrence_group_id=eq.group-1&select=" +
            "id,host_user_id,start_time,end_time,event_type,description,title,department," +
            "is_public,recurrence_group_id,is_closure";

    private static final String DETAILS =
        "\"title\":\"Study Group\",\"description\":\"Weekly review\",\"event_type\":\"Meeting\"," +
            "\"department\":\"Math\",\"is_public\":true";

    private static final String SUCCESS_BODY = "[{\"id\":7},{\"id\":8}]";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService authService = mock(AuthService.class);
    private final ReservationAvailabilityService availability = mock(ReservationAvailabilityService.class);
    private final ReservationController controller = new ReservationController(
        supabase, mapper, authService, availability
    );

    @Test
    void unknownSeriesReturnsNotFoundAndWritesNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn("[]");

        ResponseEntity<String> response = controller.updateSeries("group-1", detailsBody(""), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void facultyWhoDoesNotOwnEveryEventGetsForbiddenAndWritesNothing() throws Exception {
        var faculty = mapper.readTree("{\"id\":42,\"role_name\":\"faculty\"}");
        HttpServletRequest request = requestFor(faculty);
        when(authService.isAdmin(faculty)).thenReturn(false);
        when(authService.isFacultyOrAdmin(faculty)).thenReturn(true);
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 99, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));

        ResponseEntity<String> response = controller.updateSeries("group-1", detailsBody(""), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().contains("You do not have permission to modify this reservation."));
        verifyNoWrites();
    }

    @Test
    void seriesContainingAnUnavailableDateIsRefusedAndWritesNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(
            "[" + row(7, 42, "2030-03-05T18:00:00+00:00", "2030-03-05T19:00:00+00:00", false) + "," +
                row(8, 42, "2030-03-12T17:00:00+00:00", "2030-03-12T18:00:00+00:00", true) + "]"
        );

        ResponseEntity<String> response = controller.updateSeries("group-1", detailsBody(""), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().contains("Manage unavailable dates in Bookable Dates."));
        verifyNoWrites();
    }

    @Test
    void detailsOnlyIssuesOnePatchOnTheGroupAndNoUpsert() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, SUCCESS_BODY));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"id\":99,\"host_user_id\":5,\"start_time\":\"2031-01-01T10:00:00Z\""), request
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(SUCCESS_BODY, response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).patchResponse(eq("events?recurrence_group_id=eq.group-1"), sent.capture());
        JsonNode details = mapper.readTree(sent.getValue());
        assertEquals(5, details.size());
        assertEquals("Study Group", details.path("title").asText());
        assertEquals("Weekly review", details.path("description").asText());
        assertEquals("Meeting", details.path("event_type").asText());
        assertEquals("Math", details.path("department").asText());
        assertTrue(details.path("is_public").asBoolean());
        verify(supabase, never()).upsertResponse(anyString(), anyString());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void timeChangeIssuesOneUpsertWithEveryOccurrenceAcrossADaylightSavingChange() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        // 10:00 to 11:00 Pacific on both sides of the 2030-03-10 clock change.
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));
        when(supabase.upsertResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, SUCCESS_BODY));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"09:00\",\"local_end_time\":\"10:30\""), request
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(SUCCESS_BODY, response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).upsertResponse(eq("events?on_conflict=id"), sent.capture());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        JsonNode rows = mapper.readTree(sent.getValue());
        assertEquals(2, rows.size());
        assertEquals(7, rows.get(0).path("id").asInt());
        assertEquals("2030-03-05T17:00:00Z", rows.get(0).path("start_time").asText());
        assertEquals("2030-03-05T18:30:00Z", rows.get(0).path("end_time").asText());
        assertEquals(8, rows.get(1).path("id").asInt());
        assertEquals("2030-03-12T16:00:00Z", rows.get(1).path("start_time").asText());
        assertEquals("2030-03-12T17:30:00Z", rows.get(1).path("end_time").asText());

        for (JsonNode row : rows) {
            assertEquals(rows.get(0).propertyNames().size(), row.propertyNames().size());
            assertEquals(11, row.size());
            assertEquals(42, row.path("host_user_id").asInt());
            assertEquals("group-1", row.path("recurrence_group_id").asText());
            assertFalse(row.path("is_closure").asBoolean());
            assertEquals("Study Group", row.path("title").asText());
        }

        ArgumentCaptor<Collection<LocalDate>> dates = ArgumentCaptor.captor();
        verify(availability).validateDates(dates.capture());
        assertEquals(
            java.util.List.of(LocalDate.of(2030, 3, 5), LocalDate.of(2030, 3, 12)),
            java.util.List.copyOf(dates.getValue())
        );
    }

    @Test
    void occurrenceThatAlreadyStartedKeepsItsTimesButGetsTheDetails() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2020-01-07T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));
        when(supabase.upsertResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, SUCCESS_BODY));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"09:00\",\"local_end_time\":\"10:30\""), request
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).upsertResponse(eq("events?on_conflict=id"), sent.capture());
        JsonNode rows = mapper.readTree(sent.getValue());
        assertEquals(2, rows.size());
        assertEquals("2020-01-07T18:00:00+00:00", rows.get(0).path("start_time").asText());
        assertEquals("2020-01-07T19:00:00+00:00", rows.get(0).path("end_time").asText());
        assertEquals("Study Group", rows.get(0).path("title").asText());
        assertEquals("2030-03-12T16:00:00Z", rows.get(1).path("start_time").asText());

        ArgumentCaptor<Collection<LocalDate>> dates = ArgumentCaptor.captor();
        verify(availability).validateDates(dates.capture());
        assertEquals(java.util.List.of(LocalDate.of(2030, 3, 12)), java.util.List.copyOf(dates.getValue()));
    }

    @Test
    void onlyOneTimeFieldIsRefusedAndWritesNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"09:00\""), request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void twoOccurrencesOnOneDayRefuseATimeChangeAndWriteNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T17:00:00+00:00", "2030-03-05T21:00:00+00:00"));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"09:00\",\"local_end_time\":\"10:00\""), request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("2030-03-05"));
        verifyNoWrites();
    }

    @Test
    void malformedTimeIsRefusedAndWritesNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"9am\",\"local_end_time\":\"10:30\""), request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("local_start_time"));
        verifyNoWrites();
    }

    @Test
    void endAfterFivePmIsRefusedAndWritesNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"16:00\",\"local_end_time\":\"17:30\""), request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Reservations must end by 5:00 PM."));
        verifyNoWrites();
    }

    @Test
    void availabilityRejectionIsRefusedAndWritesNothing() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));
        doThrow(new IllegalArgumentException("Reservations are not available on 2030-03-12."))
            .when(availability).validateDates(any());

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"09:00\",\"local_end_time\":\"10:30\""), request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void overlapWithAnotherReservationBecomesConflict() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        when(supabase.get(SERIES_LOOKUP)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));
        when(supabase.upsertResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(409, "{\"code\":\"23P01\"}"));

        ResponseEntity<String> response = controller.updateSeries(
            "group-1", detailsBody(",\"local_start_time\":\"09:00\",\"local_end_time\":\"10:30\""), request
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void groupIdIsUrlEncodedInTheLookupAndTheDetailsPatch() throws Exception {
        HttpServletRequest request = requestFor(adminUser());
        String lookup = "events?recurrence_group_id=eq.a%26b%2C%28c%29+d&select=" +
            "id,host_user_id,start_time,end_time,event_type,description,title,department," +
            "is_public,recurrence_group_id,is_closure";
        when(supabase.get(lookup)).thenReturn(seriesJson(42, 42, "2030-03-05T18:00:00+00:00", "2030-03-12T17:00:00+00:00"));
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, SUCCESS_BODY));

        controller.updateSeries("a&b,(c) d", detailsBody(""), request);

        verify(supabase).get(lookup);
        verify(supabase).patchResponse(eq("events?recurrence_group_id=eq.a%26b%2C%28c%29+d"), anyString());
    }

    private String detailsBody(String extraFields) {
        return "{" + DETAILS + extraFields + "}";
    }

    private String seriesJson(int firstHost, int secondHost, String firstStart, String secondStart) {
        return "[" + row(7, firstHost, firstStart, plusOneHour(firstStart), false) + "," +
            row(8, secondHost, secondStart, plusOneHour(secondStart), false) + "]";
    }

    private String plusOneHour(String start) {
        return java.time.OffsetDateTime.parse(start).plusHours(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
    }

    private String row(int id, int host, String start, String end, boolean closure) {
        return "{\"id\":" + id + ",\"host_user_id\":" + host + ",\"start_time\":\"" + start +
            "\",\"end_time\":\"" + end + "\",\"event_type\":\"Meeting\",\"description\":\"Old\"," +
            "\"title\":\"Old title\",\"department\":\"Math\",\"is_public\":false," +
            "\"recurrence_group_id\":\"group-1\",\"is_closure\":" + closure + "}";
    }

    private JsonNode adminUser() throws Exception {
        JsonNode admin = mapper.readTree("{\"id\":5,\"role_name\":\"admin\"}");
        when(authService.isAdmin(admin)).thenReturn(true);
        return admin;
    }

    private void verifyNoWrites() {
        verify(supabase, never()).delete(anyString());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).postResponse(anyString(), anyString());
        verify(supabase, never()).upsertResponse(anyString(), anyString());
    }

    private HttpServletRequest requestFor(Object user) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("currentUser")).thenReturn(user);
        return request;
    }
}
