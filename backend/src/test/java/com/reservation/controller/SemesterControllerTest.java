package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import static com.reservation.controller.CalendarTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SemesterControllerTest {

    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final SemesterController controller = new SemesterController(supabase);

    private static final String STORED =
        "[{\"id\":3,\"name\":\"Fall 2026\",\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}]";

    // ---- GET ---------------------------------------------------------------

    @Test
    void publicListSelectsOnlyCalendarColumns() {
        when(supabase.get(anyString())).thenReturn("[]");

        ResponseEntity<String> response = controller.getAll();

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(supabase).get(query.capture());
        assertTrue(query.getValue().startsWith("semesters?select=id,name,start_date,end_date"), query.getValue());
        assertFalse(query.getValue().contains("created_by"));
        assertFalse(query.getValue().contains("*"));
    }

    // ---- POST --------------------------------------------------------------

    @Test
    void validCreateSendsOnlyWhitelistedTrimmedFieldsAndPassesStatusThrough() {
        when(supabase.postResponse(eq("semesters"), anyString()))
            .thenReturn(ok(201, "[{\"id\":9,\"name\":\"Fall 2026\"}]"));

        ResponseEntity<?> response = run(() -> controller.create("""
            {"name":"  Fall 2026  ","start_date":"2026-08-24","end_date":"2026-12-19",
             "id":77,"created_at":"2000-01-01T00:00:00Z","created_by":5}
            """));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("[{\"id\":9,\"name\":\"Fall 2026\"}]", response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq("semesters"), sent.capture());
        assertEquals(
            json("{\"name\":\"Fall 2026\",\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}"),
            json(sent.getValue())
        );
    }

    @Test
    void oneDaySemesterIsAccepted() {
        when(supabase.postResponse(eq("semesters"), anyString())).thenReturn(ok(201, "[]"));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"name\":\"Intersession\",\"start_date\":\"2027-01-04\",\"end_date\":\"2027-01-04\"}"
        ));

        assertEquals(201, response.getStatusCode().value());
    }

    @Test
    void startAfterEndIsRejectedWithoutDatabaseWrite() {
        ResponseEntity<?> response = run(() -> controller.create(
            "{\"name\":\"Bad\",\"start_date\":\"2026-12-20\",\"end_date\":\"2026-12-19\"}"
        ));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void semesterLongerThanOneYearIsRejectedButExactlyOneYearIsAccepted() {
        ResponseEntity<?> tooLong = run(() -> controller.create(
            "{\"name\":\"Long\",\"start_date\":\"2026-01-01\",\"end_date\":\"2027-01-02\"}"
        ));
        assertEquals(400, tooLong.getStatusCode().value());
        verifyNoInteractions(supabase);

        when(supabase.postResponse(eq("semesters"), anyString())).thenReturn(ok(201, "[]"));
        ResponseEntity<?> limit = run(() -> controller.create(
            "{\"name\":\"Year\",\"start_date\":\"2026-01-01\",\"end_date\":\"2027-01-01\"}"
        ));
        assertEquals(201, limit.getStatusCode().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}",
        "{\"name\":\"Fall\",\"end_date\":\"2026-12-19\"}",
        "{\"name\":\"Fall\",\"start_date\":\"2026-08-24\"}",
        "{\"name\":\"   \",\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}",
        "{\"name\":42,\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}",
        "{}",
        "[]",
        "not json"
    })
    void missingOrInvalidFieldsAreRejectedWithoutDatabaseWrite(String body) {
        ResponseEntity<?> response = run(() -> controller.create(body));

        assertEquals(400, response.getStatusCode().value(), body);
        verifyNoInteractions(supabase);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"2026/08/24\"", "\"08-24-2026\"", "\"2026-02-30\"", "\"2026-8-24\"",
        "\"2026-08-24T00:00:00Z\"", "20260824", "null", "\"\""
    })
    void badDateFormatIsRejectedWithoutDatabaseWrite(String startDate) {
        ResponseEntity<?> response = run(() -> controller.create(
            "{\"name\":\"Fall\",\"start_date\":" + startDate + ",\"end_date\":\"2026-12-19\"}"
        ));

        assertEquals(400, response.getStatusCode().value(), startDate);
        verifyNoInteractions(supabase);
    }

    @Test
    void overlapMapsTo409() {
        when(supabase.postResponse(eq("semesters"), anyString())).thenReturn(dbError(
            409, "23P01", "Key (daterange(start_date, end_date, '[]'::text))=(...) conflicts with existing key", "conflicting key value violates exclusion constraint \"semesters_no_overlap\""
        ));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"name\":\"Fall\",\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}"
        ));

        assertEquals(409, response.getStatusCode().value());
        assertTrue(json(response).path("error").asText().contains("overlaps"));
    }

    @Test
    void duplicateNameMapsTo409() {
        when(supabase.postResponse(eq("semesters"), anyString())).thenReturn(dbError(
            409, "23505", "Key (lower(btrim(name)))=(fall) already exists.", "duplicate key value"
        ));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"name\":\"FALL\",\"start_date\":\"2027-08-24\",\"end_date\":\"2027-12-19\"}"
        ));

        assertEquals(409, response.getStatusCode().value());
        assertTrue(json(response).path("error").asText().contains("name"));
    }

    @Test
    void checkViolationMapsTo400AndUnknownErrorsTo502WithoutLeakingDetail() {
        when(supabase.postResponse(eq("semesters"), anyString()))
            .thenReturn(dbError(400, "23514", "Failing row contains (secret)", "violates check"))
            .thenReturn(new SupabaseClient.SupabaseResponse(503, "<html>upstream down</html>"));
        String body = "{\"name\":\"Fall\",\"start_date\":\"2026-08-24\",\"end_date\":\"2026-12-19\"}";

        ResponseEntity<?> check = run(() -> controller.create(body));
        ResponseEntity<?> unknown = run(() -> controller.create(body));

        assertEquals(400, check.getStatusCode().value());
        assertFalse(check.getBody().toString().contains("secret"));
        assertEquals(502, unknown.getStatusCode().value());
        assertFalse(unknown.getBody().toString().contains("upstream"));
    }

    // ---- PATCH -------------------------------------------------------------

    @Test
    void partialPatchIsValidatedAgainstStoredDates() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);

        // Stored start is 2026-08-24, so an end before it must be refused.
        ResponseEntity<?> response = run(() -> controller.update(3, "{\"end_date\":\"2026-08-01\"}"));

        assertEquals(400, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void partialPatchExtendingBeyondOneYearFromStoredStartIsRejected() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);

        ResponseEntity<?> response = run(() -> controller.update(3, "{\"end_date\":\"2027-08-26\"}"));

        assertEquals(400, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void validPartialPatchSendsOnlyTheChangedFieldAndPassesStatusThrough() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        when(supabase.patchResponse(eq("semesters?id=eq.3"), anyString()))
            .thenReturn(ok(200, "[{\"id\":3}]"));

        ResponseEntity<?> response = run(() -> controller.update(3, "{\"start_date\":\"2026-09-01\",\"id\":8}"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("[{\"id\":3}]", response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).patchResponse(eq("semesters?id=eq.3"), sent.capture());
        assertEquals(json("{\"start_date\":\"2026-09-01\"}"), json(sent.getValue()));
    }

    @Test
    void nameOnlyPatchIsAccepted() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        when(supabase.patchResponse(eq("semesters?id=eq.3"), anyString())).thenReturn(ok(200, "[{\"id\":3}]"));

        ResponseEntity<?> response = run(() -> controller.update(3, "{\"name\":\" Fall 2026 (rev) \"}"));

        assertEquals(200, response.getStatusCode().value());
        verify(supabase).patchResponse("semesters?id=eq.3", "{\"name\":\"Fall 2026 (rev)\"}");
    }

    @Test
    void emptyPatchIsRejected() {
        ResponseEntity<?> response = run(() -> controller.update(3, "{\"created_at\":\"x\"}"));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void patchOfMissingSemesterIs404WithoutWrite() {
        when(supabase.get(contains("semesters?id=eq.404"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.update(404, "{\"name\":\"X\"}"));

        assertEquals(404, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void patchThatMatchesNoRowIs404() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        when(supabase.patchResponse(eq("semesters?id=eq.3"), anyString())).thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.update(3, "{\"name\":\"X\"}"));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void strandingPatchMapsBtc03To409WithStructuredDetails() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        JsonNode details = json("""
            {"semester_id":3,
             "reservations":[{"id":11,"title":"Lab","start_time":"2026-12-10T18:00:00+00:00",
                              "end_time":"2026-12-10T19:00:00+00:00","host_user_id":2}],
             "unavailable_days":[{"id":4,"unavailable_date":"2026-12-15"}],
             "internal":"must not leak"}
            """);
        when(supabase.patchResponse(eq("semesters?id=eq.3"), anyString()))
            .thenReturn(dbError(400, "BTC03", details, "This change would leave reservations ..."));

        ResponseEntity<?> response = run(() -> controller.update(3, "{\"end_date\":\"2026-12-01\"}"));

        assertEquals(409, response.getStatusCode().value());
        JsonNode body = json(response);
        assertEquals(3, body.path("semester_id").asInt());
        assertTrue(body.path("reservations").isArray(), "reservations must be a JSON array, not a string");
        assertEquals(11, body.path("reservations").get(0).path("id").asInt());
        assertEquals("2026-12-15", body.path("unavailable_days").get(0).path("unavailable_date").asText());
        assertFalse(body.has("internal"));
        assertTrue(body.hasNonNull("error"));
    }

    // ---- DELETE ------------------------------------------------------------

    @Test
    void deleteOfMissingSemesterIs404WithoutDelete() {
        when(supabase.get(contains("semesters?id=eq.404"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.delete(404));

        assertEquals(404, response.getStatusCode().value());
        verify(supabase, never()).delete(anyString());
    }

    @Test
    void successfulDeleteIs204() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        when(supabase.delete("semesters?id=eq.3")).thenReturn(ok(204, ""));

        ResponseEntity<?> response = run(() -> controller.delete(3));

        assertEquals(204, response.getStatusCode().value());
    }

    @Test
    void strandingDeleteMapsBtc03To409WithDetails() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        when(supabase.delete("semesters?id=eq.3")).thenReturn(dbError(
            400, "BTC03",
            json("{\"semester_id\":3,\"reservations\":[{\"id\":11}],\"unavailable_days\":[]}"),
            "..."
        ));

        ResponseEntity<?> response = run(() -> controller.delete(3));

        assertEquals(409, response.getStatusCode().value());
        JsonNode body = json(response);
        assertEquals(11, body.path("reservations").get(0).path("id").asInt());
        assertTrue(body.path("unavailable_days").isArray());
    }

    @Test
    void deleteBlockedByForeignKeyIs409AndOtherFailuresAre502() {
        when(supabase.get(contains("semesters?id=eq.3"))).thenReturn(STORED);
        when(supabase.delete("semesters?id=eq.3"))
            .thenReturn(dbError(409, "23503", "Key is still referenced", "fk"))
            .thenReturn(new SupabaseClient.SupabaseResponse(500, "oops"));

        assertEquals(409, run(() -> controller.delete(3)).getStatusCode().value());
        assertEquals(502, run(() -> controller.delete(3)).getStatusCode().value());
    }
}
