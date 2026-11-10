package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import com.reservation.services.ReservationAvailabilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

class ReservationAvailabilityTest {
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthService auth = mock(AuthService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ReservationAvailabilityService availability = new ReservationAvailabilityService(supabase, mapper);
    private final ReservationAvailabilityController settings = new ReservationAvailabilityController(availability);
    private final ReservationController events = new ReservationController(supabase, mapper, auth, availability);

    @BeforeEach
    void setup() {
        when(request.getAttribute("currentUser")).thenReturn(mapper.readTree("{\"id\":1,\"role_name\":\"admin\"}"));
        when(auth.isAdmin(any())).thenReturn(true);
        when(supabase.postResponse(eq("events"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(201, "[]"));
    }

    @Test
    void blockedDateIsRejectedBeforeAnyWrite() {
        configure("2099-01-31", "2099-01-04");
        var result = events.create(event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z"), request);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertTrue(result.getBody().contains("2099-01-04"));
        verify(supabase, never()).postResponse(eq("events"), anyString());
    }

    @Test
    void cutoffIsInclusiveAndUsesPacificDateRatherThanUtcDate() {
        configure("2099-01-04");
        var result = events.create(event("2099-01-05T00:30:00Z", "2099-01-05T01:00:00Z"), request);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        var tooLate = events.create(event("2099-01-05T16:00:00Z", "2099-01-05T17:00:00Z"), request);
        assertEquals(HttpStatus.BAD_REQUEST, tooLate.getStatusCode());
        verify(supabase, times(1)).postResponse(eq("events"), anyString());
    }

    @Test
    void localTimestampsAreStoredAsTheSamePacificInstantThatWasValidated() {
        configure("2099-01-04", "2099-01-03");
        var result = events.create(event("2099-01-04T16:30:00", "2099-01-04T17:00:00"), request);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        verify(supabase).postResponse(eq("events"), argThat(body ->
            mapper.readTree(body).path("start_time").asText().equals("2099-01-05T00:30:00Z") &&
            mapper.readTree(body).path("end_time").asText().equals("2099-01-05T01:00:00Z")));
    }

    @Test
    void batchDoesNotPartiallySaveWhenOneDateIsBlocked() {
        configure("2099-01-31", "2099-01-05");
        var result = events.createBatch("[" + event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z") + "," +
            event("2099-01-05T16:00:00Z", "2099-01-05T17:00:00Z") + "]", request);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        verify(supabase, never()).postResponse(eq("events"), anyString());
        verify(supabase, times(1)).get(eq("rpc/get_reservation_availability"));
    }

    @Test
    void everyRecurringOccurrenceIsCheckedBeforeWriting() {
        configure("2099-01-31", "2099-01-11");
        var body = mapper.createObjectNode();
        body.set("event", mapper.readTree(event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z")));
        body.putArray("ranges").addObject().put("start_time", "2099-01-04T16:00:00Z")
            .put("end_time", "2099-01-04T17:00:00Z").put("week_count", 2);
        var result = events.createRecurring(body.toString(), request);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertTrue(result.getBody().contains("2099-01-11"));
        verify(supabase, never()).postResponse(eq("events"), anyString());
    }

    @Test
    void unavailableOrUnconfiguredSettingsNeverAllowBooking() {
        when(supabase.get(eq("rpc/get_reservation_availability"))).thenReturn("[]");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, events.create(
            event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z"), request).getStatusCode());
        when(supabase.get(eq("rpc/get_reservation_availability"))).thenReturn(
            "{\"last_bookable_date\":null,\"invalid_dates\":[],\"version\":0}");
        assertEquals(HttpStatus.BAD_REQUEST, events.create(
            event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z"), request).getStatusCode());
        verify(supabase, never()).postResponse(eq("events"), anyString());
    }

    @Test
    void changingTimesChecksAvailabilityButMetadataEditsKeepExistingBookings() {
        configure("2099-01-31", "2099-01-04", "2099-01-05");
        var existing = mapper.readTree(event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z"));
        when(supabase.get(startsWith("events?id=eq.7"))).thenReturn("[" + existing + "]");
        when(supabase.patchResponse(eq("events?id=eq.7"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));
        assertEquals(HttpStatus.OK, events.update(7, existing.toString(), request).getStatusCode());
        verify(supabase, never()).get(eq("rpc/get_reservation_availability"));
        var changed = events.update(7, event("2099-01-05T16:00:00Z", "2099-01-05T17:00:00Z"), request);
        assertEquals(HttpStatus.BAD_REQUEST, changed.getStatusCode());
        verify(supabase, times(1)).patchResponse(eq("events?id=eq.7"), anyString());
    }

    @Test
    void settingsSaveIsAtomicAndDetectsAnotherAdminsChanges() {
        var body = mapper.readTree("{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[\"2099-01-04\"],\"version\":3}");
        when(supabase.postResponse(eq("rpc/save_reservation_availability"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200,
                "{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[\"2099-01-04\"],\"version\":4}"));
        assertEquals(HttpStatus.OK, settings.save(body).getStatusCode());
        verify(supabase).postResponse(eq("rpc/save_reservation_availability"), argThat(json ->
            mapper.readTree(json).path("p_version").asLong() == 3 && json.contains("2099-01-04")));
        when(supabase.postResponse(eq("rpc/save_reservation_availability"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(409, "{\"code\":\"40001\"}"));
        assertEquals(HttpStatus.CONFLICT, settings.save(body).getStatusCode());
    }

    @Test
    void malformedSettingsAndImpossibleDatesAreRejected() {
        for (String json : new String[] {
            "{\"last_bookable_date\":\"2099-02-30\",\"invalid_dates\":[],\"version\":0}",
            "{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[\"2099-02-01\"],\"version\":0}",
            "{\"last_bookable_date\":null,\"invalid_dates\":[],\"version\":0}",
            "{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[],\"version\":-1}"
        }) {
            assertEquals(HttpStatus.BAD_REQUEST, settings.save(mapper.readTree(json)).getStatusCode());
        }
        verify(supabase, never()).postResponse(eq("rpc/save_reservation_availability"), anyString());
    }

    @Test
    void databaseReadFailuresReturnServiceUnavailable() {
        when(supabase.get(eq("rpc/get_reservation_availability"))).thenThrow(new RuntimeException("private database detail"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, settings.get().getStatusCode());
        assertFalse(settings.get().getBody().toString().contains("private database detail"));
    }

    @Test
    void httpEndpointsReadAndSaveJsonSettings() throws Exception {
        configure("2099-01-31", "2099-01-04");
        var http = MockMvcBuilders.standaloneSetup(settings).build();
        var read = http.perform(get("/api/reservation-availability")).andReturn().getResponse();
        assertEquals(200, read.getStatus());
        assertEquals("2099-01-31", mapper.readTree(read.getContentAsString()).path("last_bookable_date").asText());
        when(supabase.postResponse(eq("rpc/save_reservation_availability"), anyString())).thenReturn(
            new SupabaseClient.SupabaseResponse(200,
                "{\"last_bookable_date\":\"2099-02-28\",\"invalid_dates\":[],\"version\":1}"));
        var saved = http.perform(patch("/api/reservation-availability").contentType(MediaType.APPLICATION_JSON)
            .content("{\"last_bookable_date\":\"2099-02-28\",\"invalid_dates\":[],\"version\":0}"))
            .andReturn().getResponse();
        assertEquals(200, saved.getStatus());
        assertEquals(1, mapper.readTree(saved.getContentAsString()).path("version").asInt());
    }

    @Test
    void ordinaryEventWritesCannotCreateOrChangeClosures() {
        var input = mapper.readTree(event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z")).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) input).put("is_closure", true);
        assertEquals(HttpStatus.BAD_REQUEST, events.create(input.toString(), request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, events.createBatch("[" + input + "]", request).getStatusCode());
        var recurring = mapper.createObjectNode();
        recurring.set("event", input);
        recurring.putArray("ranges").addObject().put("start_time", "2099-01-04T16:00:00Z")
            .put("end_time", "2099-01-04T17:00:00Z").put("week_count", 2);
        assertEquals(HttpStatus.BAD_REQUEST, events.createRecurring(recurring.toString(), request).getStatusCode());
        when(supabase.get(startsWith("events?id=eq.7"))).thenReturn("[" + input + "]");
        assertEquals(HttpStatus.FORBIDDEN, events.update(7, input.toString(), request).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, events.delete(7, request).getStatusCode());
        verify(supabase, never()).postResponse(eq("events"), anyString());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).delete(anyString());
    }

    @Test
    void publicClosuresKeepTheirDetailsAndMarkerInTheEventFeed() {
        String closure = "[{\"id\":7,\"is_closure\":true,\"is_public\":true,\"title\":\"Unavailable\"}]";
        when(supabase.get("events?select=*,users(first_name,last_name)")).thenReturn(closure);
        var result = mapper.readTree(events.getAll(request).getBody()).get(0);
        assertTrue(result.path("is_closure").asBoolean());
        assertEquals("Unavailable", result.path("title").asText());
    }

    private void configure(String cutoff, String... invalidDates) {
        var row = mapper.createObjectNode().put("last_bookable_date", cutoff).put("version", 0);
        var dates = row.putArray("invalid_dates");
        for (String date : invalidDates) dates.add(date);
        when(supabase.get(eq("rpc/get_reservation_availability"))).thenReturn(row.toString());
    }

    @Test
    void cancellationRequiresReviewAndPassesConfirmedIdsToTheAtomicSave() {
        var input = mapper.readTree("{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[\"2099-01-04\"],\"version\":0}");
        when(supabase.postResponse(eq("rpc/save_reservation_availability"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200,
                "{\"requires_confirmation\":true,\"reservations\":[{\"id\":7,\"title\":\"Planned meeting\"}]}"));
        var review = settings.save(input);
        assertEquals(HttpStatus.CONFLICT, review.getStatusCode());
        assertTrue(mapper.valueToTree(review.getBody()).path("conflicting_reservations").isArray());
        ((tools.jackson.databind.node.ObjectNode) input).putArray("confirmed_cancellations").add(7);
        when(supabase.postResponse(eq("rpc/save_reservation_availability"), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200,
                "{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[\"2099-01-04\"],\"version\":1}"));
        assertEquals(HttpStatus.OK, settings.save(input).getStatusCode());
        verify(supabase).postResponse(eq("rpc/save_reservation_availability"), argThat(json ->
            mapper.readTree(json).path("p_confirmed_cancellations").path(0).asInt() == 7));
    }

    @Test
    void invalidCancellationIdsNeverReachTheDatabase() {
        for (String ids : new String[] { "[null]", "[-1]", "[1.5]", "[\"7\"]", "{}" }) {
            var input = mapper.readTree("{\"last_bookable_date\":\"2099-01-31\",\"invalid_dates\":[],\"version\":0,\"confirmed_cancellations\":" + ids + "}");
            assertEquals(HttpStatus.BAD_REQUEST, settings.save(input).getStatusCode());
        }
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void existingDeletionFlowRemovesCheckInsBeforeTheReservation() {
        when(supabase.get(startsWith("events?id=eq.7"))).thenReturn("[{\"id\":7,\"host_user_id\":1}]");
        when(supabase.delete("attendees?event_id=eq.7")).thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));
        when(supabase.delete("events?id=eq.7")).thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));
        assertEquals(HttpStatus.NO_CONTENT, events.delete(7, request).getStatusCode());
        var order = inOrder(supabase);
        order.verify(supabase).delete("attendees?event_id=eq.7");
        order.verify(supabase).delete("events?id=eq.7");
    }

    private String event(String start, String end) {
        return """
            {"host_user_id":1,"start_time":"%s","end_time":"%s","event_type":"Meeting",
             "description":"Meeting details","title":"Meeting","department":"Math","is_public":true}
            """.formatted(start, end);
    }

    @Test
    void overlappingSingleAndBatchBookingsReturnConflictWithoutPrivateDetails() {
        configure("2099-01-31");
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(overlapResponse());
        String booking = event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z");
        for (var result : java.util.List.of(events.create(booking, request),
                events.createBatch("[" + booking + "," + booking + "]", request))) {
            assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
            assertEquals("This time overlaps an existing reservation. Choose a different time.",
                mapper.readTree(result.getBody()).path("error").asText());
            assertFalse(result.getBody().contains("Private meeting"));
            assertFalse(result.getBody().contains("events_no_reservation_overlap"));
        }
    }

    @Test
    void overlappingRecurringBookingReturnsConflict() {
        configure("2099-01-31");
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(overlapResponse());
        var body = mapper.createObjectNode();
        body.set("event", mapper.readTree(event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z")));
        body.putArray("ranges").addObject().put("start_time", "2099-01-04T16:00:00Z")
            .put("end_time", "2099-01-04T17:00:00Z").put("week_count", 2);
        assertEquals(HttpStatus.CONFLICT, events.createRecurring(body.toString(), request).getStatusCode());
        verify(supabase, times(1)).postResponse(eq("events"), anyString());
    }

    @Test
    void reschedulingIntoAnotherReservationReturnsConflict() {
        configure("2099-01-31");
        when(supabase.get(startsWith("events?id=eq.7"))).thenReturn("[" +
            event("2099-01-04T18:00:00Z", "2099-01-04T19:00:00Z") + "]");
        when(supabase.patchResponse(eq("events?id=eq.7"), anyString())).thenReturn(overlapResponse());
        assertEquals(HttpStatus.CONFLICT, events.update(7,
            event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z"), request).getStatusCode());
    }

    @Test
    void otherDatabaseErrorsStayGeneric() {
        configure("2099-01-31");
        for (String failure : new String[] { "{\"code\":\"23503\",\"details\":\"Private meeting\"}", "Bad gateway", "null" }) {
            when(supabase.postResponse(eq("events"), anyString()))
                .thenReturn(new SupabaseClient.SupabaseResponse(500, failure));
            var result = events.create(event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z"), request);
            assertEquals(HttpStatus.BAD_GATEWAY, result.getStatusCode());
            assertEquals("The database could not complete this request.", mapper.readTree(result.getBody()).path("error").asText());
        }
    }

    private SupabaseClient.SupabaseResponse overlapResponse() {
        return new SupabaseClient.SupabaseResponse(409,
            "{\"code\":\"23P01\",\"message\":\"events_no_reservation_overlap\",\"details\":\"Private meeting\"}");
    }

    @Test
    void concurrentWriteAbortsReturnRetryableConflicts() {
        configure("2099-01-31");
        for (String code : new String[] { "40P01", "40001" }) {
            when(supabase.postResponse(eq("events"), anyString())).thenReturn(
                new SupabaseClient.SupabaseResponse(500, "{\"code\":\"" + code + "\",\"details\":\"Private meeting\"}"));
            var result = events.createBatch("[" + event("2099-01-04T16:00:00Z", "2099-01-04T17:00:00Z") + "]", request);
            assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
            assertEquals("Another reservation changed while saving. Please try again.",
                mapper.readTree(result.getBody()).path("error").asText());
        }
    }
}
