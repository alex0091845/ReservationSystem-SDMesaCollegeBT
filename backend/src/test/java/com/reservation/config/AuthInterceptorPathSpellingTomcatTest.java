package com.reservation.config;

import com.reservation.services.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Runs the real application on embedded Tomcat (database client and session lookup
 * mocked; nothing leaves the JVM) and sends a faculty session's write requests to the
 * admin-only calendar endpoints using alternative spellings of the same path. Tomcat
 * and Spring MVC route all of them to the same controller, so the admin check must
 * hold for each one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "supabase.url=http://127.0.0.1:9",
        "supabase.api-key=unused-test-key",
        "app.cors.allowed-origin-patterns=https://frontend.example"
    }
)
class AuthInterceptorPathSpellingTomcatTest {

    @MockitoBean
    SupabaseClient supabase;

    @MockitoBean
    SessionService sessions;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void facultySession() {
        when(sessions.getCurrentUser(any()))
            .thenReturn(new ObjectMapper().readTree("{\"id\":42,\"role_name\":\"faculty\",\"enabled\":true}"));
        when(supabase.postResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(201, "[{\"id\":1}]"));
    }

    private int get(String rawPath) throws Exception {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode();
    }

    private void storedClosureAndBookings() {
        when(supabase.get(startsWith("unavailable_days?id=eq.8")))
            .thenReturn("[{\"id\":8,\"semester_id\":1,\"unavailable_date\":\"2026-12-02\",\"reason\":\"x\"}]");
        when(supabase.get(startsWith("events?")))
            .thenReturn("[{\"id\":31,\"title\":\"Private\",\"is_public\":false}]");
    }

    private static final String[] AFFECTED_SPELLINGS = {
        "/api/unavailable-days/8/affected-reservations",
        "/api/unavailable-days/8/affected-reservations;x=1",
        "/api/unavailable-days;x=1/8/affected-reservations",
        "/api/unavailable-days/8;x=1/affected-reservations",
        "/api;x=1/unavailable-days/8/affected-reservations",
        "/api/unavailable%2Ddays/8/affected-reservations",
        "/api/unavailable-days/8/affected%2Dreservations",
        "/api/unavailable-days/%38/affected-reservations",
        "/api/%75navailable-days/8/affected-reservations",
        "/api/unavailable-days/8/affected-reservations/",
        "/api//unavailable-days/8/affected-reservations",
        "/api/unavailable-days//8/affected-reservations",
        "/api/./unavailable-days/8/affected-reservations",
        "/api/unavailable-days/./8/affected-reservations",
        "/api/x/../unavailable-days/8/affected-reservations",
        "/api/unavailable-days/9/../8/affected-reservations",
        "/API/unavailable-days/8/affected-reservations",
        "/api/unavailable-days/8/affected-reservations%20",
        "/api/unavailable-days/8/affected-reservations?x=1"
    };

    static String[] affectedSpellings() {
        return AFFECTED_SPELLINGS;
    }

    @ParameterizedTest
    @MethodSource("affectedSpellings")
    void facultyCannotReadAffectedReservations(String rawPath) throws Exception {
        storedClosureAndBookings();

        int status = get(rawPath);

        verify(supabase, never()).get(startsWith("events"));
        assertTrue(status >= 400, rawPath + " answered " + status + " to a faculty read");
    }

    @ParameterizedTest
    @MethodSource("affectedSpellings")
    void anonymousCannotReadAffectedReservations(String rawPath) throws Exception {
        storedClosureAndBookings();
        when(sessions.getCurrentUser(any())).thenReturn(null);

        int status = get(rawPath);

        verify(supabase, never()).get(startsWith("events"));
        assertTrue(status >= 400, rawPath + " answered " + status + " to an anonymous read");
    }

    // Positive control: the canonical path really reaches the controller for an admin, so
    // the refusals above are the admin rule at work and not a routing accident.
    @Test
    void adminCanReadAffectedReservations() throws Exception {
        storedClosureAndBookings();
        when(sessions.getCurrentUser(any()))
            .thenReturn(new ObjectMapper().readTree("{\"id\":5,\"role_name\":\"admin\",\"enabled\":true}"));

        int status = get("/api/unavailable-days/8/affected-reservations");

        assertEquals(200, status);
        verify(supabase).get(startsWith("events?start_time=lt.2026-12-03T08:00:00Z&end_time=gt.2026-12-02T08:00:00Z"));
    }

    @Test
    void anonymousCanStillReadTheClosureList() throws Exception {
        when(sessions.getCurrentUser(any())).thenReturn(null);
        when(supabase.get(startsWith("unavailable_days?select="))).thenReturn("[]");

        assertEquals(200, get("/api/unavailable-days"));
    }

    private int post(String rawPath, String body) throws Exception {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/semesters", "/api/semesters;x=1", "/api/%73emesters", "/api/semesters/",
        "/api//semesters", "/api/./semesters", "/api/x/../semesters", "/api;x=1/semesters",
        "/api/semesters%3Bx=1", "/api/sem%65sters;x=1/", "/API/semesters", "/api/semesters%20"
    })
    void facultyCannotCreateASemester(String rawPath) throws Exception {
        int status = post(rawPath, "{\"name\":\"Rogue\",\"start_date\":\"2027-01-01\",\"end_date\":\"2027-01-31\"}");

        verify(supabase, never()).postResponse(anyString(), anyString());
        assertTrue(status >= 400, rawPath + " answered " + status + " to a faculty write");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/unavailable-days", "/api/unavailable-days;x=1", "/api/unavailable%2Ddays",
        "/api//unavailable-days", "/api/./unavailable-days", "/api/x/../unavailable-days"
    })
    void facultyCannotCloseADay(String rawPath) throws Exception {
        LocalDate future = LocalDate.now(ZoneId.of("America/Los_Angeles")).plusDays(10);

        int status = post(rawPath, "{\"unavailable_date\":\"" + future + "\"}");

        verify(supabase, never()).postResponse(anyString(), anyString());
        assertTrue(status >= 400, rawPath + " answered " + status + " to a faculty write");
    }
}
