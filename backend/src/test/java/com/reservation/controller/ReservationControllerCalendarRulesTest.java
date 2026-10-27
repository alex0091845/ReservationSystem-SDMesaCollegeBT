package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;

import static com.reservation.controller.CalendarTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationControllerCalendarRulesTest {

    private static final String CREATE_RPC = "rpc/create_events_with_calendar_override";
    private static final String UPDATE_RPC = "rpc/update_event_with_calendar_override";

    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService authService = mock(AuthService.class);
    private final ReservationController controller =
        new ReservationController(supabase, new ObjectMapper(), authService);

    private final JsonNode admin = user(5, "admin");
    private final JsonNode faculty = user(42, "faculty");
    private final HttpServletRequest adminRequest = requestFor(admin);
    private final HttpServletRequest facultyRequest = requestFor(faculty);

    private final LocalDate day = todayLA().plusDays(30);

    @BeforeEach
    void roles() {
        when(authService.isAdmin(admin)).thenReturn(true);
        when(authService.isFacultyOrAdmin(admin)).thenReturn(true);
        when(authService.isAdmin(faculty)).thenReturn(false);
        when(authService.isFacultyOrAdmin(faculty)).thenReturn(true);
    }

    private static String local(LocalDate date, int hour) {
        return ZonedDateTime.of(date, LocalTime.of(hour, 0), LA).toOffsetDateTime().toString();
    }

    private static String instant(LocalDate date, int hour) {
        return ZonedDateTime.of(date, LocalTime.of(hour, 0), LA).toInstant().toString();
    }

    private String event(int host, LocalDate date) {
        return eventFields(host, local(date, 10), local(date, 11));
    }

    private static String eventFields(int host, String start, String end) {
        return """
            {"host_user_id":%d,"start_time":"%s","end_time":"%s","event_type":"Meeting",
             "description":"Desc","title":"Title","department":"Math","is_public":true}
            """.formatted(host, start, end);
    }

    private static String withOverride(String eventJson, String extra) {
        String trimmed = eventJson.trim();
        return trimmed.substring(0, trimmed.length() - 1) + "," + extra + "}";
    }

    private void stubExistingEvent(int id, int host) {
        when(supabase.get(contains("select=id,host_user_id")))
            .thenReturn("[{\"id\":" + id + ",\"host_user_id\":" + host + "}]");
    }

    private static JsonNode btc01(String dates, String unavailable, String outside) {
        return json("{\"dates\":" + dates + ",\"unavailable_dates\":" + unavailable +
            ",\"outside_semester_dates\":" + outside + "}");
    }

    // ---- BTC01 mapping -----------------------------------------------------

    @Test
    void closedDayMapsBtc01To409WithAllDateFields() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(dbError(
            400, "BTC01", btc01("[\"" + day + "\"]", "[\"" + day + "\"]", "[]"), "Reservation date is unavailable."
        ));

        ResponseEntity<?> response = run(() -> controller.create(event(42, day), facultyRequest));

        assertEquals(409, response.getStatusCode().value());
        JsonNode body = json(response);
        assertEquals("Reservation date is unavailable.", body.path("error").asText());
        assertEquals(day.toString(), body.path("date").asText());
        assertTrue(body.path("dates").isArray(), "dates must be a JSON array");
        assertEquals(day.toString(), body.path("dates").get(0).asText());
        assertEquals(day.toString(), body.path("unavailable_dates").get(0).asText());
        assertTrue(body.path("outside_semester_dates").isArray());
        assertTrue(body.path("outside_semester_dates").isEmpty());
    }

    @Test
    void outsideSemesterOnlyUsesTheSemesterMessage() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(dbError(
            400, "BTC01", btc01("[\"" + day + "\"]", "[]", "[\"" + day + "\"]"), "m"
        ));

        ResponseEntity<?> response = run(() -> controller.create(event(42, day), facultyRequest));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("Reservation date is outside the configured semester.", json(response).path("error").asText());
        assertEquals(day.toString(), json(response).path("outside_semester_dates").get(0).asText());
    }

    @Test
    void btc01WithoutUsableDetailsIsStill409AndOtherErrorsAre502() {
        when(supabase.postResponse(eq("events"), anyString()))
            .thenReturn(dbError(400, "BTC01", "not json at all", "m"))
            .thenReturn(dbError(400, "23514", "Failing row contains (secret)", "check"))
            .thenReturn(new SupabaseClient.SupabaseResponse(500, ""));

        ResponseEntity<?> noDetails = run(() -> controller.create(event(42, day), facultyRequest));
        ResponseEntity<?> other = run(() -> controller.create(event(42, day), facultyRequest));
        ResponseEntity<?> empty = run(() -> controller.create(event(42, day), facultyRequest));

        assertEquals(409, noDetails.getStatusCode().value());
        assertTrue(json(noDetails).hasNonNull("error"));
        assertFalse(json(noDetails).has("date"));
        assertEquals(502, other.getStatusCode().value());
        assertFalse(other.getBody().toString().contains("secret"));
        assertEquals(502, empty.getStatusCode().value());
    }

    @Test
    void adminWithoutOverrideUsesNormalTableWriteAndIsStillRefused() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(dbError(
            400, "BTC01", btc01("[\"" + day + "\"]", "[\"" + day + "\"]", "[]"), "m"
        ));

        ResponseEntity<?> response = run(() -> controller.create(event(99, day), adminRequest));

        assertEquals(409, response.getStatusCode().value());
        verify(supabase).postResponse(eq("events"), anyString());
        verify(supabase, never()).postResponse(eq(CREATE_RPC), anyString());
    }

    @Test
    void timesWithoutOffsetAreSentAsLosAngelesInstants() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(ok(201, "[]"));

        run(() -> controller.create(
            eventFields(42, day + "T10:00:00", day + "T11:30:00"), facultyRequest
        ));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq("events"), sent.capture());
        JsonNode row = json(sent.getValue());
        assertEquals(instant(day, 10), row.path("start_time").asText());
        assertEquals(
            ZonedDateTime.of(day, LocalTime.of(11, 30), LA).toInstant().toString(),
            row.path("end_time").asText()
        );
    }

    // ---- Faculty may not override ------------------------------------------

    @Test
    void facultyOverrideOnCreateIs403WithNoDatabaseCall() {
        ResponseEntity<?> response = run(() -> controller.create(
            withOverride(event(42, day), "\"admin_override\":true,\"override_reason\":\"Please\""), facultyRequest
        ));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void facultyOverrideWithoutReasonIsStillRefusedWithNoDatabaseCall() {
        ResponseEntity<?> response = run(() -> controller.create(
            withOverride(event(42, day), "\"admin_override\":true"), facultyRequest
        ));

        assertTrue(response.getStatusCode().value() == 403 || response.getStatusCode().value() == 400);
        verifyNoInteractions(supabase);
    }

    @Test
    void facultyOverrideOnBatchIs403WithNoDatabaseCall() {
        ResponseEntity<?> response = run(() -> controller.createBatch(
            "{\"events\":[" + event(42, day) + "," + event(42, day.plusDays(1)) + "]," +
                "\"admin_override\":true,\"override_reason\":\"x\"}",
            facultyRequest
        ));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void facultyOverrideOnRecurringIs403WithNoDatabaseCall() {
        ResponseEntity<?> response = run(() -> controller.createRecurring(
            recurringBody(42, day, 3, "\"admin_override\":true,\"override_reason\":\"x\""), facultyRequest
        ));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void facultyOverrideOnPatchOfOwnEventIs403WithNoWrite() {
        stubExistingEvent(7, 42);

        ResponseEntity<?> response = run(() -> controller.update(
            7, withOverride(event(42, day), "\"admin_override\":true,\"override_reason\":\"x\""), facultyRequest
        ));

        assertEquals(403, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void facultyOverrideFalseIsANormalWrite() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(ok(201, "[{\"id\":1}]"));

        ResponseEntity<?> response = run(() -> controller.create(
            withOverride(event(42, day), "\"admin_override\":false"), facultyRequest
        ));

        assertEquals(201, response.getStatusCode().value());
        verify(supabase).postResponse(eq("events"), anyString());
    }

    // ---- Admin override ----------------------------------------------------

    @Test
    void adminOverrideCreateUsesRpcWithSessionActorNotBodyActor() {
        when(supabase.postResponse(eq(CREATE_RPC), anyString())).thenReturn(ok(200, "[{\"id\":10}]"));

        ResponseEntity<?> response = run(() -> controller.create(withOverride(event(99, day),
            "\"admin_override\":true,\"override_reason\":\"  Accreditation visit  \"," +
                "\"p_actor_user_id\":77,\"actor_user_id\":77,\"user_id\":77"), adminRequest));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("[{\"id\":10}]", response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq(CREATE_RPC), sent.capture());
        verify(supabase, never()).postResponse(eq("events"), anyString());
        JsonNode call = json(sent.getValue());
        assertEquals(5, call.path("p_actor_user_id").asInt());
        assertEquals("Accreditation visit", call.path("p_reason").asText());
        assertTrue(call.path("p_events").isArray());
        assertEquals(1, call.path("p_events").size());
        JsonNode row = call.path("p_events").get(0);
        assertEquals(99, row.path("host_user_id").asInt());
        assertEquals(instant(day, 10), row.path("start_time").asText());
        assertFalse(row.has("admin_override"));
        assertFalse(row.has("override_reason"));
        assertFalse(row.has("p_actor_user_id"));
        assertEquals(3, call.size(), "only p_actor_user_id, p_reason, p_events: " + call);
    }

    @Test
    void adminOverridePatchUsesRpcWithSessionActorAndPassesStatusThrough() {
        stubExistingEvent(7, 99);
        when(supabase.postResponse(eq(UPDATE_RPC), anyString())).thenReturn(ok(200, "[{\"id\":7}]"));

        ResponseEntity<?> response = run(() -> controller.update(7, withOverride(event(99, day),
            "\"admin_override\":true,\"override_reason\":\"Dean approved\",\"p_actor_user_id\":77,\"p_event_id\":8"),
            adminRequest));

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq(UPDATE_RPC), sent.capture());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        JsonNode call = json(sent.getValue());
        assertEquals(5, call.path("p_actor_user_id").asInt());
        assertEquals(7, call.path("p_event_id").asInt());
        assertEquals("Dean approved", call.path("p_reason").asText());
        assertEquals("Title", call.path("p_event").path("title").asText());
        assertFalse(call.path("p_event").has("admin_override"));
        assertFalse(call.path("p_event").has("id"));
    }

    @Test
    void adminOverridePatchWithEmptyRpcResultIs404() {
        stubExistingEvent(7, 99);
        when(supabase.postResponse(eq(UPDATE_RPC), anyString())).thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.update(7, withOverride(event(99, day),
            "\"admin_override\":true,\"override_reason\":\"x\""), adminRequest));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void adminOverridePatchOfMissingEventIs404WithoutRpc() {
        when(supabase.get(contains("select=id,host_user_id"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.update(7, withOverride(event(99, day),
            "\"admin_override\":true,\"override_reason\":\"x\""), adminRequest));

        assertEquals(404, response.getStatusCode().value());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void overrideRefusedByDatabaseMapsBta01To403() {
        when(supabase.postResponse(eq(CREATE_RPC), anyString()))
            .thenReturn(dbError(400, "BTA01", (JsonNode) null, "Only an enabled administrator ..."));

        ResponseEntity<?> response = run(() -> controller.create(withOverride(event(99, day),
            "\"admin_override\":true,\"override_reason\":\"x\""), adminRequest));

        assertEquals(403, response.getStatusCode().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"admin_override\":true",
        "\"admin_override\":true,\"override_reason\":\"\"",
        "\"admin_override\":true,\"override_reason\":\"   \"",
        "\"admin_override\":true,\"override_reason\":null",
        "\"admin_override\":true,\"override_reason\":12",
        "\"admin_override\":true,\"override_reason\":\"LONG\"",
        "\"admin_override\":\"true\",\"override_reason\":\"x\"",
        "\"admin_override\":1,\"override_reason\":\"x\"",
        "\"admin_override\":{},\"override_reason\":\"x\""
    })
    void invalidAdminOverrideIs400WithoutWrite(String extra) {
        String fields = extra.replace("LONG", "r".repeat(501));

        ResponseEntity<?> create = run(() -> controller.create(withOverride(event(99, day), fields), adminRequest));
        stubExistingEvent(7, 99);
        ResponseEntity<?> patch = run(() -> controller.update(7, withOverride(event(99, day), fields), adminRequest));
        ResponseEntity<?> batch = run(() -> controller.createBatch(
            "{\"events\":[" + event(99, day) + "]," + fields + "}", adminRequest
        ));
        ResponseEntity<?> recurring = run(() -> controller.createRecurring(
            recurringBody(99, day, 2, fields), adminRequest
        ));

        assertEquals(400, create.getStatusCode().value(), "create " + extra);
        assertEquals(400, patch.getStatusCode().value(), "patch " + extra);
        assertEquals(400, batch.getStatusCode().value(), "batch " + extra);
        assertEquals(400, recurring.getStatusCode().value(), "recurring " + extra);
        verify(supabase, never()).postResponse(anyString(), anyString());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void reasonOfExactly500CharactersIsAccepted() {
        when(supabase.postResponse(eq(CREATE_RPC), anyString())).thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.create(withOverride(event(99, day),
            "\"admin_override\":true,\"override_reason\":\"" + "r".repeat(500) + "\""), adminRequest));

        assertEquals(201, response.getStatusCode().value());
    }

    // ---- Batch and recurring are single writes ------------------------------

    @Test
    void batchIsASingleWriteAndBtc01NamesEveryDate() {
        LocalDate second = day.plusDays(1);
        LocalDate third = day.plusDays(2);
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(dbError(400, "BTC01", btc01(
            "[\"" + day + "\",\"" + third + "\"]", "[\"" + day + "\"]", "[\"" + third + "\"]"
        ), "m"));

        ResponseEntity<?> response = run(() -> controller.createBatch(
            "{\"events\":[" + event(42, day) + "," + event(42, second) + "," + event(42, third) + "]}",
            facultyRequest
        ));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).postResponse(eq("events"), sent.capture());
        assertEquals(3, json(sent.getValue()).size());
        assertEquals(409, response.getStatusCode().value());
        JsonNode body = json(response);
        assertEquals(day.toString(), body.path("date").asText());
        assertEquals(2, body.path("dates").size());
        assertEquals(third.toString(), body.path("dates").get(1).asText());
        assertEquals(day.toString(), body.path("unavailable_dates").get(0).asText());
        assertEquals(third.toString(), body.path("outside_semester_dates").get(0).asText());
    }

    @Test
    void perReservationOverrideFlagsInsideABatchAreNeverHonoured() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(ok(201, "[]"));
        String flagged = withOverride(event(99, day), "\"admin_override\":true,\"override_reason\":\"inner\"");

        ResponseEntity<?> response = run(() -> controller.createBatch(
            "{\"events\":[" + flagged + "]}", adminRequest
        ));

        // Only a top-level override counts; the inner flag must not reach the RPC or the row.
        assertEquals(201, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq("events"), sent.capture());
        verify(supabase, never()).postResponse(eq(CREATE_RPC), anyString());
        assertFalse(sent.getValue().contains("admin_override"));
        assertFalse(sent.getValue().contains("override_reason"));
    }

    @Test
    void arrayFormBatchIsASingleWrite() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(ok(201, "[]"));

        ResponseEntity<?> response = run(() -> controller.createBatch(
            "[" + event(42, day) + "," + event(42, day.plusDays(1)) + "]", facultyRequest
        ));

        assertEquals(201, response.getStatusCode().value());
        verify(supabase, times(1)).postResponse(eq("events"), anyString());
    }

    @Test
    void adminOverrideBatchIsASingleRpc() {
        when(supabase.postResponse(eq(CREATE_RPC), anyString())).thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.createBatch(
            "{\"events\":[" + event(99, day) + "," + event(99, day.plusDays(1)) + "]," +
                "\"admin_override\":true,\"override_reason\":\"Finals week\"}",
            adminRequest
        ));

        assertEquals(201, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).postResponse(eq(CREATE_RPC), sent.capture());
        verify(supabase, never()).postResponse(eq("events"), anyString());
        assertEquals(2, json(sent.getValue()).path("p_events").size());
        assertEquals(5, json(sent.getValue()).path("p_actor_user_id").asInt());
    }

    private static String recurringBody(int host, LocalDate firstDay, int weeks, String extra) {
        return """
            {"event":{"host_user_id":%d,"event_type":"Meeting","description":"Desc","title":"Weekly",
                      "department":"Math","is_public":true,"recurrence_group_id":"series-1"},
             "ranges":[{"start_time":"%s","end_time":"%s","week_count":%d}]%s}
            """.formatted(host, local(firstDay, 10), local(firstDay, 11), weeks,
            extra == null ? "" : "," + extra);
    }

    @Test
    void recurringSeriesIsASingleWriteThatKeepsLocalTimeAcrossDst() {
        // A weekly series that crosses the next DST change must stay at 10:00 local.
        LocalDate first = firstWeekBeforeNextDstChange();
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(ok(201, "[]"));

        ResponseEntity<?> response = run(() -> controller.createRecurring(
            recurringBody(42, first, 3, null), facultyRequest
        ));

        assertEquals(201, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).postResponse(eq("events"), sent.capture());
        JsonNode rows = json(sent.getValue());
        assertEquals(3, rows.size());
        for (int week = 0; week < 3; week++) {
            ZonedDateTime start = Instant.parse(rows.get(week).path("start_time").asText()).atZone(LA);
            assertEquals(first.plusWeeks(week), start.toLocalDate());
            assertEquals(LocalTime.of(10, 0), start.toLocalTime(), "week " + week + " drifted: " + start);
        }
    }

    @Test
    void recurringWithExcludedWeeksAndBtc01IsAllOrNothing() {
        when(supabase.postResponse(eq("events"), anyString())).thenReturn(dbError(400, "BTC01", btc01(
            "[\"" + day.plusWeeks(2) + "\"]", "[\"" + day.plusWeeks(2) + "\"]", "[]"
        ), "m"));

        ResponseEntity<?> response = run(() -> controller.createRecurring("""
            {"event":{"host_user_id":42,"event_type":"Meeting","description":"Desc","title":"Weekly",
                      "department":"Math","is_public":true},
             "ranges":[{"start_time":"%s","end_time":"%s","week_count":4,"excluded_week_offsets":[1]}]}
            """.formatted(local(day, 10), local(day, 11)), facultyRequest));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).postResponse(eq("events"), sent.capture());
        assertEquals(3, json(sent.getValue()).size());
        assertEquals(409, response.getStatusCode().value());
        assertEquals(day.plusWeeks(2).toString(), json(response).path("date").asText());
    }

    @Test
    void adminOverrideRecurringIsASingleRpc() {
        when(supabase.postResponse(eq(CREATE_RPC), anyString())).thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.createRecurring(
            recurringBody(99, day, 4, "\"admin_override\":true,\"override_reason\":\"Summer bridge\""),
            adminRequest
        ));

        assertEquals(201, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(1)).postResponse(eq(CREATE_RPC), sent.capture());
        assertEquals(4, json(sent.getValue()).path("p_events").size());
    }

    // ---- PATCH without override --------------------------------------------

    @Test
    void ownerPatchWithoutOverrideUsesTableWriteAndMapsBtc01() {
        stubExistingEvent(7, 42);
        when(supabase.patchResponse(eq("events?id=eq.7"), anyString())).thenReturn(dbError(
            400, "BTC01", btc01("[\"" + day + "\"]", "[\"" + day + "\"]", "[]"), "m"
        ));

        ResponseEntity<?> response = run(() -> controller.update(7, event(42, day), facultyRequest));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(day.toString(), json(response).path("date").asText());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    // ---- Ownership is unchanged --------------------------------------------

    @Test
    void facultyCannotCreateForAnotherHostEvenWithOverride() {
        ResponseEntity<?> plain = run(() -> controller.create(event(99, day), facultyRequest));
        ResponseEntity<?> overridden = run(() -> controller.create(
            withOverride(event(99, day), "\"admin_override\":true,\"override_reason\":\"x\""), facultyRequest
        ));

        assertEquals(403, plain.getStatusCode().value());
        assertEquals(403, overridden.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void facultyCannotPatchAnotherHostsEventEvenWithOverride() {
        stubExistingEvent(7, 99);

        ResponseEntity<?> response = run(() -> controller.update(7, withOverride(event(42, day),
            "\"admin_override\":true,\"override_reason\":\"x\""), facultyRequest));

        assertEquals(403, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void anonymousCannotCreateOrOverride() {
        HttpServletRequest anonymous = requestFor(null);

        ResponseEntity<?> response = run(() -> controller.create(
            withOverride(event(42, day), "\"admin_override\":true,\"override_reason\":\"x\""), anonymous
        ));

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    private LocalDate firstWeekBeforeNextDstChange() {
        Instant cursor = Instant.now();
        while (true) {
            ZoneOffsetTransition transition = LA.getRules().nextTransition(cursor);
            LocalDate changeDay = transition.getInstant().atZone(LA).toLocalDate();
            LocalDate first = changeDay.minusWeeks(1);
            if (first.isAfter(todayLA())) {
                return first;
            }
            cursor = transition.getInstant().plusSeconds(1);
        }
    }
}
