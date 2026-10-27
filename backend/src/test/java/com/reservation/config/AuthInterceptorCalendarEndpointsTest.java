package com.reservation.config;

import com.reservation.controller.SemesterController;
import com.reservation.controller.UnavailableDayController;
import com.reservation.services.AuthService;
import com.reservation.services.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AuthInterceptorCalendarEndpointsTest {

    private final SessionService sessions = mock(SessionService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(
        sessions, auth, new String[] {"https://frontend.example"}
    );
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode admin = mapper.readTree("{\"id\":5,\"role_name\":\"admin\"}");
    private final JsonNode faculty = mapper.readTree("{\"id\":42,\"role_name\":\"faculty\"}");

    @BeforeEach
    void roles() {
        when(auth.isAdmin(admin)).thenReturn(true);
        when(auth.isFacultyOrAdmin(admin)).thenReturn(true);
        when(auth.isAdmin(faculty)).thenReturn(false);
        when(auth.isFacultyOrAdmin(faculty)).thenReturn(true);
    }

    private HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/semesters", "/api/semesters/3", "/api/unavailable-days"})
    void readsArePublicWithoutASession(String path) throws Exception {
        HttpServletRequest request = request("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(null);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/api/semesters", "PATCH,/api/semesters/3", "DELETE,/api/semesters/3",
        "POST,/api/unavailable-days", "PATCH,/api/unavailable-days/8", "DELETE,/api/unavailable-days/8",
        "PUT,/api/semesters/3", "POST,/api/semesters/"
    })
    void writesWithoutASessionAre401(String method, String path) throws Exception {
        HttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/api/semesters", "PATCH,/api/semesters/3", "DELETE,/api/semesters/3",
        "POST,/api/unavailable-days", "PATCH,/api/unavailable-days/8", "DELETE,/api/unavailable-days/8",
        "PUT,/api/unavailable-days/8", "POST,/api/unavailable-days/"
    })
    void writesByNonAdminsAre403(String method, String path) throws Exception {
        HttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(faculty);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/api/semesters", "PATCH,/api/semesters/3", "DELETE,/api/semesters/3",
        "POST,/api/unavailable-days", "PATCH,/api/unavailable-days/8", "DELETE,/api/unavailable-days/8"
    })
    void writesByAdminsPassAndExposeTheSessionUser(String method, String path) throws Exception {
        HttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(admin);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(request).setAttribute("currentUser", admin);
    }

    @Test
    void lookalikePathsAreNotTreatedAsCalendarEndpoints() throws Exception {
        HttpServletRequest request = request("GET", "/api/semestersX");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    // ---- End to end through Spring MVC routing -------------------------------
    //
    // The interceptor decides on the raw request URI, while Spring MVC routes on the
    // decoded path without ;matrix parameters. These tests send faculty write requests
    // through the real dispatcher and assert that none reaches the database.

    private MockMvc mvc(SupabaseClient supabase) {
        return MockMvcBuilders
            .standaloneSetup(new SemesterController(supabase), new UnavailableDayController(supabase))
            .addInterceptors(interceptor)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/semesters",
        "/api/semesters;a=b",
        "/api/%73emesters",
        "/api/semesters%2F"
    })
    void facultyCannotCreateSemesterThroughAnyPathSpelling(String rawPath) throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(supabase.postResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(201, "[{\"id\":1}]"));
        when(sessions.getCurrentUser(any())).thenReturn(faculty);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request("POST", URI.create(rawPath))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rogue\",\"start_date\":\"2027-01-01\",\"end_date\":\"2027-01-31\"}"))
            .andReturn();

        int status = result.getResponse().getStatus();
        verify(supabase, never()).postResponse(anyString(), anyString());
        // The security property is "no write"; an unroutable spelling may surface as any
        // error status (the catch-all handler turns Spring's 404 into 500 in standalone MVC).
        assertTrue(status >= 400, rawPath + " returned " + status + " for a faculty write");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/unavailable-days",
        "/api/unavailable-days;a=b",
        "/api/unavailable%2Ddays"
    })
    void facultyCannotCreateClosureThroughAnyPathSpelling(String rawPath) throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(supabase.postResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(201, "[{\"id\":1}]"));
        when(sessions.getCurrentUser(any())).thenReturn(faculty);
        LocalDate future = LocalDate.now(ZoneId.of("America/Los_Angeles")).plusDays(10);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request("POST", URI.create(rawPath))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unavailable_date\":\"" + future + "\"}"))
            .andReturn();

        int status = result.getResponse().getStatus();
        verify(supabase, never()).postResponse(anyString(), anyString());
        // The security property is "no write"; an unroutable spelling may surface as any
        // error status (the catch-all handler turns Spring's 404 into 500 in standalone MVC).
        assertTrue(status >= 400, rawPath + " returned " + status + " for a faculty write");
    }

    // ---- Admin-only paths below /api/unavailable-days/ -----------------------------
    //
    // Only the list itself is public. Every path below it, for every method, needs an
    // admin session: the affected-reservations list exposes private bookings and hosts.

    @ParameterizedTest
    @CsvSource({
        "GET,/api/unavailable-days/8/affected-reservations",
        "GET,/api/unavailable-days/8",
        "GET,/api/unavailable-days/",
        "GET,/api/unavailable-days/8/affected-reservations/",
        "GET,/api/unavailable-days/8/affected-reservations;x=1",
        "GET,/api/unavailable-days;x=1/8/affected-reservations",
        "GET,/api/unavailable%2Ddays/8/affected-reservations",
        "GET,/api/unavailable-days/8/affected%2Dreservations",
        "HEAD,/api/unavailable-days/8/affected-reservations",
        "POST,/api/unavailable-days/8/affected-reservations",
        "DELETE,/api/unavailable-days/8/affected-reservations"
    })
    void pathsBelowTheClosureListNeedASession(String method, String path) throws Exception {
        HttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()), method + " " + path);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus(), method + " " + path);
    }

    @ParameterizedTest
    @CsvSource({
        "GET,/api/unavailable-days/8/affected-reservations",
        "GET,/api/unavailable-days/8",
        "GET,/api/unavailable-days/",
        "GET,/api/unavailable-days/8/affected-reservations/",
        "GET,/api/unavailable-days/8/affected-reservations;x=1",
        "GET,/api/unavailable-days;x=1/8/affected-reservations",
        "GET,/api/unavailable%2Ddays/8/affected-reservations",
        "GET,/api/unavailable-days/8/affected%2Dreservations",
        "GET,/api/unavailable-days/%38/affected-reservations",
        "HEAD,/api/unavailable-days/8/affected-reservations",
        "get,/api/unavailable-days/8/affected-reservations"
    })
    void pathsBelowTheClosureListAreForbiddenToFaculty(String method, String path) throws Exception {
        HttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(faculty);

        assertFalse(interceptor.preHandle(request, response, new Object()), method + " " + path);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus(), method + " " + path);
    }

    @Test
    void adminsReachTheAffectedReservationsListWithTheSessionUserSet() throws Exception {
        HttpServletRequest request = request("GET", "/api/unavailable-days/8/affected-reservations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.getCurrentUser(request)).thenReturn(admin);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(request).setAttribute("currentUser", admin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/unavailable-days", "/api/unavailable-days;x=1", "/api/unavailable%2Ddays"})
    void theClosureListItselfStaysPublic(String path) throws Exception {
        HttpServletRequest request = request("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()), path);
        assertEquals(200, response.getStatus());
        verify(sessions, never()).getCurrentUser(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/unavailable-days/8/affected-reservations",
        "/api/unavailable-days/8/affected-reservations;a=b",
        "/api/unavailable-days;a=b/8/affected-reservations",
        "/api/unavailable%2Ddays/8/affected-reservations",
        "/api/unavailable-days/8/affected%2Dreservations",
        "/api/unavailable-days/%38/affected-reservations"
    })
    void facultyCannotReadAffectedReservationsThroughAnyPathSpelling(String rawPath) throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(supabase.get(anyString())).thenReturn(
            "[{\"id\":8,\"semester_id\":1,\"unavailable_date\":\"2026-12-02\",\"reason\":\"x\"}]");
        when(sessions.getCurrentUser(any())).thenReturn(faculty);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request("GET", URI.create(rawPath)))
            .andReturn();

        verifyNoInteractions(supabase);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, result.getResponse().getStatus(), rawPath);
    }

    @Test
    void anonymousCannotReadAffectedReservationsThroughMvc() throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(sessions.getCurrentUser(any())).thenReturn(null);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request(
                "GET", URI.create("/api/unavailable-days/8/affected-reservations")))
            .andReturn();

        verifyNoInteractions(supabase);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getResponse().getStatus());
    }

    @Test
    void adminReadsAffectedReservationsThroughMvc() throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(supabase.get(org.mockito.ArgumentMatchers.startsWith("unavailable_days?id=eq.8"))).thenReturn(
            "[{\"id\":8,\"semester_id\":1,\"unavailable_date\":\"2026-12-02\",\"reason\":\"x\"}]");
        when(supabase.get(org.mockito.ArgumentMatchers.startsWith("events?"))).thenReturn("[{\"id\":31}]");
        when(sessions.getCurrentUser(any())).thenReturn(admin);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request(
                "GET", URI.create("/api/unavailable-days/8/affected-reservations")))
            .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("[{\"id\":31}]", result.getResponse().getContentAsString());
        verify(supabase).get(org.mockito.ArgumentMatchers.startsWith("events?start_time=lt.2026-12-03T08:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "-", "99999999999999999999"})
    void malformedClosureIdsNeverReachTheDatabase(String id) throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(sessions.getCurrentUser(any())).thenReturn(admin);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request(
                "GET", URI.create("/api/unavailable-days/" + id + "/affected-reservations")))
            .andReturn();

        int status = result.getResponse().getStatus();
        verifyNoInteractions(supabase);
        assertTrue(status >= 400, id + " returned " + status);
    }

    @Test
    void unknownClosureThroughMvcIs404ForAdmins() throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(supabase.get(anyString())).thenReturn("[]");
        when(sessions.getCurrentUser(any())).thenReturn(admin);

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request(
                "GET", URI.create("/api/unavailable-days/999/affected-reservations")))
            .andReturn();

        assertEquals(404, result.getResponse().getStatus());
        verify(supabase, never()).get(org.mockito.ArgumentMatchers.startsWith("events"));
    }

    @Test
    void publicReadThroughMvcReturnsCalendarColumnsOnly() throws Exception {
        SupabaseClient supabase = mock(SupabaseClient.class);
        when(supabase.get(anyString())).thenReturn("[]");

        MvcResult result = mvc(supabase).perform(MockMvcRequestBuilders.request("GET", URI.create("/api/unavailable-days?from=2026-11-01")))
            .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        verify(supabase).get(
            "unavailable_days?select=id,semester_id,unavailable_date,reason&unavailable_date=gte.2026-11-01" +
                "&order=unavailable_date.asc"
        );
        verifyNoInteractions(sessions);
    }
}
