package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

import static com.reservation.controller.CalendarTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class UnavailableDayControllerTest {

    private static final String[] PRIVATE_COLUMNS = {
        "created_by", "override_by", "override_reason", "affected_reservation_ids", "created_at", "*"
    };

    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final UnavailableDayController controller = new UnavailableDayController(supabase);
    private final JsonNode admin = user(5, "admin");
    private final HttpServletRequest adminRequest = requestFor(admin);

    private final LocalDate future = todayLA().plusDays(20);
    private final LocalDate past = todayLA().minusDays(1);

    private String storedClosure(long id, LocalDate date) {
        return "[{\"id\":" + id + ",\"semester_id\":1,\"unavailable_date\":\"" + date + "\",\"reason\":\"Holiday\"}]";
    }

    // ---- GET ---------------------------------------------------------------

    @Test
    void publicListExcludesCreatorAndOverrideColumns() {
        when(supabase.get(anyString())).thenReturn("[]");

        controller.getAll(null, null, null);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(supabase).get(query.capture());
        assertTrue(query.getValue().startsWith("unavailable_days?select=id,semester_id,unavailable_date,reason&"),
            query.getValue());
        for (String column : PRIVATE_COLUMNS) {
            assertFalse(query.getValue().contains(column), column + " leaked into " + query.getValue());
        }
        assertTrue(query.getValue().endsWith("&order=unavailable_date.asc"));
    }

    @Test
    void filtersAreTranslatedToPostgrestOperators() {
        when(supabase.get(anyString())).thenReturn("[]");

        controller.getAll("2026-11-01", "2026-11-30", "7");

        verify(supabase).get(
            "unavailable_days?select=id,semester_id,unavailable_date,reason" +
                "&unavailable_date=gte.2026-11-01&unavailable_date=lte.2026-11-30&semester_id=eq.7" +
                "&order=unavailable_date.asc"
        );
    }

    @Test
    void sameDayRangeIsAccepted() {
        when(supabase.get(anyString())).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.getAll("2026-11-01", "2026-11-01", null));

        assertEquals(200, response.getStatusCode().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "from=2026-13-01", "to=11/02/2026", "from=2026-11-02&to=2026-11-01", "semester_id=abc",
        "semester_id=1.5", "from=2026-11-01&select=created_by", "semester_id=1&created_by=eq.5"
    })
    void invalidFiltersAreRejectedWithoutQuery(String filter) {
        String from = null;
        String to = null;
        String semesterId = null;
        for (String part : filter.split("&(?=(from|to|semester_id)=)")) {
            String[] kv = part.split("=", 2);
            switch (kv[0]) {
                case "from" -> from = kv[1];
                case "to" -> to = kv[1];
                case "semester_id" -> semesterId = kv[1];
                default -> fail(part);
            }
        }
        String f = from;
        String t = to;
        String s = semesterId;

        ResponseEntity<?> response = run(() -> controller.getAll(f, t, s));

        assertEquals(400, response.getStatusCode().value(), filter);
        verifyNoInteractions(supabase);
    }

    // ---- GET /{id}/affected-reservations -------------------------------------

    private static final String AFFECTED_SELECT =
        "&select=id,title,start_time,end_time,is_public,host_user_id,users(first_name,last_name,email)" +
            "&order=start_time.asc,id.asc";

    @Test
    void affectedReservationsOfAnUnknownClosureIs404WithoutAnEventsQuery() {
        when(supabase.get(contains("unavailable_days?id=eq.404"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.getAffectedReservations(404));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unavailable day not found.", json(response).path("error").asText());
        verify(supabase, never()).get(startsWith("events"));
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void affectedReservationsLookUpTheClosureByIdWithPublicColumnsOnly() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, LocalDate.parse("2026-12-02")));
        when(supabase.get(startsWith("events?"))).thenReturn("[]");

        run(() -> controller.getAffectedReservations(8));

        verify(supabase).get("unavailable_days?id=eq.8&select=id,semester_id,unavailable_date,reason");
    }

    /**
     * The query must cover exactly one Los Angeles day: [local midnight, next local midnight).
     * Expected bounds are written out by hand (not computed the way the controller does) so a
     * zone or offset mistake cannot cancel itself out. On the spring-forward day the window is
     * 23 hours long, on the fall-back day 25 hours. Strict lt/gt means a reservation that ends
     * exactly at the day's first midnight, or starts exactly at the next one, is not listed.
     */
    @ParameterizedTest
    @CsvSource({
        // standard time (UTC-8)
        "2026-12-02, 2026-12-02T08:00:00Z, 2026-12-03T08:00:00Z",
        // daylight time (UTC-7)
        "2027-07-01, 2027-07-01T07:00:00Z, 2027-07-02T07:00:00Z",
        // spring forward: 02:00 PST -> 03:00 PDT, a 23-hour day
        "2027-03-14, 2027-03-14T08:00:00Z, 2027-03-15T07:00:00Z",
        // fall back: 02:00 PDT -> 01:00 PST, a 25-hour day
        "2026-11-01, 2026-11-01T07:00:00Z, 2026-11-02T08:00:00Z",
        "2027-11-07, 2027-11-07T07:00:00Z, 2027-11-08T08:00:00Z",
        // the day before each change still has the old offset all day
        "2027-03-13, 2027-03-13T08:00:00Z, 2027-03-14T08:00:00Z",
        "2026-10-31, 2026-10-31T07:00:00Z, 2026-11-01T07:00:00Z"
    })
    void affectedReservationsQueryCoversExactlyTheLosAngelesDay(String date, String dayStart, String nextMidnight) {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, LocalDate.parse(date)));
        when(supabase.get(startsWith("events?"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.getAffectedReservations(8));

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(supabase, times(2)).get(query.capture());
        String eventsQuery = query.getAllValues().get(1);
        assertEquals("events?start_time=lt." + nextMidnight + "&end_time=gt." + dayStart + AFFECTED_SELECT, eventsQuery);
        // A '+' offset would be decoded as a space by PostgREST; UTC 'Z' instants avoid it.
        assertFalse(eventsQuery.contains("+"), eventsQuery);
    }

    @Test
    void affectedReservationsUseTheStoredDateNotAnythingFromTheRequest() {
        LocalDate stored = LocalDate.parse("2026-12-02");
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, stored));
        when(supabase.get(startsWith("events?"))).thenReturn("[]");

        run(() -> controller.getAffectedReservations(8));

        verify(supabase).get(
            "events?start_time=lt.2026-12-03T08:00:00Z&end_time=gt.2026-12-02T08:00:00Z" + AFFECTED_SELECT);
    }

    @Test
    void anEmptyAffectedListIsReturnedAsIs() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.get(startsWith("events?"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.getAffectedReservations(8));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("[]", response.getBody());
    }

    @Test
    void affectedReservationsArePassedThroughUnchangedIncludingPrivateOnes() {
        String rows = """
            [{"id":31,"title":"Private advising","start_time":"2026-12-02T17:00:00+00:00",\
            "end_time":"2026-12-02T18:00:00+00:00","is_public":false,"host_user_id":2,\
            "users":{"first_name":"Fay","last_name":"Faculty","email":"faculty@sdccd.edu"}}]""";
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, LocalDate.parse("2026-12-02")));
        when(supabase.get(startsWith("events?"))).thenReturn(rows);

        ResponseEntity<?> response = run(() -> controller.getAffectedReservations(8));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(rows, response.getBody());
    }

    @Test
    void affectedReservationsAreRecomputedOnEveryCall() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.get(startsWith("events?")))
            .thenReturn("[{\"id\":31},{\"id\":32}]")
            .thenReturn("[{\"id\":32}]")
            .thenReturn("[]");

        assertEquals("[{\"id\":31},{\"id\":32}]", run(() -> controller.getAffectedReservations(8)).getBody());
        assertEquals("[{\"id\":32}]", run(() -> controller.getAffectedReservations(8)).getBody());
        assertEquals("[]", run(() -> controller.getAffectedReservations(8)).getBody());
        verify(supabase, times(3)).get(startsWith("events?"));
    }

    @Test
    void affectedReservationsNeverWrite() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.get(startsWith("events?"))).thenReturn("[]");

        run(() -> controller.getAffectedReservations(8));

        verify(supabase, never()).postResponse(anyString(), anyString());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).delete(anyString());
    }

    // ---- POST --------------------------------------------------------------

    @Test
    void createTakesCreatedByFromSessionAndNeverForwardsBodyOnlyFields() {
        when(supabase.postResponse(eq("unavailable_days"), anyString())).thenReturn(ok(201, "[{\"id\":1}]"));

        ResponseEntity<?> response = run(() -> controller.create("""
            {"unavailable_date":"%s","reason":"  Thanksgiving  ","created_by":999,"id":12,
             "override_by":999,"override_reason":"forged","affected_reservation_ids":[1,2],
             "created_at":"2000-01-01T00:00:00Z"}
            """.formatted(future), adminRequest));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("[{\"id\":1}]", response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq("unavailable_days"), sent.capture());
        assertEquals(
            json("{\"unavailable_date\":\"" + future + "\",\"reason\":\"Thanksgiving\",\"created_by\":5}"),
            json(sent.getValue())
        );
    }

    @Test
    void createForTodayIsAllowed() {
        when(supabase.postResponse(eq("unavailable_days"), anyString())).thenReturn(ok(201, "[]"));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + todayLA() + "\"}", adminRequest
        ));

        assertEquals(201, response.getStatusCode().value());
    }

    @Test
    void pastDateIs400WithNoDatabaseWrite() {
        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + past + "\",\"reason\":\"x\"}", adminRequest
        ));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void pastDateWithOverrideIsStill400WithNoDatabaseWrite() {
        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + past + "\",\"admin_override\":true,\"override_reason\":\"Flood\"}",
            adminRequest
        ));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"unavailable_date\":null}", "{\"unavailable_date\":\"2026/11/26\"}",
        "{\"unavailable_date\":20261126}", "{\"unavailable_date\":\"FUTURE\",\"reason\":42}",
        "{\"unavailable_date\":\"FUTURE\",\"semester_id\":\"1\"}",
        "{\"unavailable_date\":\"FUTURE\",\"semester_id\":1.5}",
        "{\"unavailable_date\":\"FUTURE\",\"reason\":\"LONG\"}",
        "[]", "nope"
    })
    void invalidCreateBodiesAre400WithoutWrite(String template) {
        String body = template.replace("FUTURE", future.toString()).replace("LONG", "x".repeat(201));

        ResponseEntity<?> response = run(() -> controller.create(body, adminRequest));

        assertEquals(400, response.getStatusCode().value(), body);
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void duplicateDateMapsTo409() {
        when(supabase.postResponse(eq("unavailable_days"), anyString())).thenReturn(dbError(
            409, "23505", "Key (unavailable_date)=(2026-11-26) already exists.", "duplicate key"
        ));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + future + "\"}", adminRequest
        ));

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void btc02MapsTo409WithReservationDetailsAsJson() {
        JsonNode details = json("""
            {"date":"%s","reservations":[
              {"id":31,"title":"Lab","start_time":"2026-11-26T18:00:00+00:00",
               "end_time":"2026-11-26T19:00:00+00:00","host_user_id":2}]}
            """.formatted(future));
        when(supabase.postResponse(eq("unavailable_days"), anyString()))
            .thenReturn(dbError(400, "BTC02", details, "Reservations already exist on this date."));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + future + "\"}", adminRequest
        ));

        assertEquals(409, response.getStatusCode().value());
        JsonNode body = json(response);
        assertEquals(future.toString(), body.path("date").asText());
        assertTrue(body.path("reservations").isArray());
        assertEquals(31, body.path("reservations").get(0).path("id").asInt());
        assertEquals("Lab", body.path("reservations").get(0).path("title").asText());
        // The admin is told that the day can still be closed with an explicit override.
        assertTrue(body.path("override_available").isBoolean(), body.toString());
        assertTrue(body.path("override_available").asBoolean(), body.toString());
    }

    @Test
    void btc04MapsTo409AndBtc05To400() {
        when(supabase.postResponse(eq("unavailable_days"), anyString()))
            .thenReturn(dbError(400, "BTC04", json("{\"date\":\"" + future + "\",\"semester_id\":4}"), "m"))
            .thenReturn(dbError(400, "BTC05", json("{\"date\":\"" + future + "\"}"), "m"));
        String body = "{\"unavailable_date\":\"" + future + "\"}";

        ResponseEntity<?> outside = run(() -> controller.create(body, adminRequest));
        ResponseEntity<?> inPast = run(() -> controller.create(body, adminRequest));

        assertEquals(409, outside.getStatusCode().value());
        assertEquals(future.toString(), json(outside).path("date").asText());
        assertEquals(4, json(outside).path("semester_id").asInt());
        assertEquals(400, inPast.getStatusCode().value());
        assertEquals(future.toString(), json(inPast).path("date").asText());
        // An override never bypasses the semester or past-date rules, so it must not be offered.
        assertFalse(json(outside).has("override_available"), json(outside).toString());
        assertFalse(json(inPast).has("override_available"), json(inPast).toString());
    }

    // ---- Emergency closure (admin override) --------------------------------

    @Test
    void emergencyClosureGoesToRpcWithSessionActorAndReturns201() {
        when(supabase.postResponse(eq("rpc/create_unavailable_day_with_override"), anyString()))
            .thenReturn(ok(200, "[{\"id\":8,\"semester_id\":1,\"unavailable_date\":\"" + future + "\",\"reason\":\"Burst pipe\"}]"));

        ResponseEntity<?> response = run(() -> controller.create("""
            {"unavailable_date":"%s","reason":"Burst pipe","admin_override":true,
             "override_reason":"  Water damage  ","p_actor_user_id":77,"actor_user_id":77,
             "override_by":77,"created_by":77,"affected_reservation_ids":[99]}
            """.formatted(future), adminRequest));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("[{\"id\":8,\"semester_id\":1,\"unavailable_date\":\"" + future + "\",\"reason\":\"Burst pipe\"}]",
            response.getBody());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq("rpc/create_unavailable_day_with_override"), sent.capture());
        verify(supabase, never()).postResponse(eq("unavailable_days"), anyString());
        assertEquals(json("""
            {"p_actor_user_id":5,"p_reason":"Water damage",
             "p_day":{"unavailable_date":"%s","reason":"Burst pipe","created_by":5}}
            """.formatted(future)), json(sent.getValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":true}",
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":true,\"override_reason\":\"   \"}",
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":true,\"override_reason\":null}",
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":true,\"override_reason\":7}",
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":true,\"override_reason\":\"LONG\"}",
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":\"true\",\"override_reason\":\"x\"}",
        "{\"unavailable_date\":\"FUTURE\",\"admin_override\":1,\"override_reason\":\"x\"}"
    })
    void invalidOverrideIs400WithoutWrite(String template) {
        String body = template.replace("FUTURE", future.toString()).replace("LONG", "r".repeat(501));

        ResponseEntity<?> response = run(() -> controller.create(body, adminRequest));

        assertEquals(400, response.getStatusCode().value(), body);
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void overrideFalseUsesTheNormalTableWrite() {
        when(supabase.postResponse(eq("unavailable_days"), anyString())).thenReturn(ok(201, "[]"));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + future + "\",\"admin_override\":false,\"override_reason\":\"x\"}",
            adminRequest
        ));

        assertEquals(201, response.getStatusCode().value());
        verify(supabase).postResponse(eq("unavailable_days"), anyString());
        verify(supabase, never()).postResponse(contains("rpc/"), anyString());
    }

    @Test
    void emergencyClosureRefusedByDatabaseMapsBta01To403() {
        when(supabase.postResponse(eq("rpc/create_unavailable_day_with_override"), anyString()))
            .thenReturn(dbError(400, "BTA01", (JsonNode) null, "Only an enabled administrator ..."));

        ResponseEntity<?> response = run(() -> controller.create(
            "{\"unavailable_date\":\"" + future + "\",\"admin_override\":true,\"override_reason\":\"Flood\"}",
            adminRequest
        ));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void emergencyClosureStillMapsRuleErrorsFromTheRpc() {
        when(supabase.postResponse(eq("rpc/create_unavailable_day_with_override"), anyString()))
            .thenReturn(dbError(400, "BTC04", json("{\"date\":\"" + future + "\"}"), "m"))
            .thenReturn(dbError(409, "23505", "Key (unavailable_date) already exists.", "dup"));
        String body = "{\"unavailable_date\":\"" + future + "\",\"admin_override\":true,\"override_reason\":\"Flood\"}";

        assertEquals(409, run(() -> controller.create(body, adminRequest)).getStatusCode().value());
        assertEquals(409, run(() -> controller.create(body, adminRequest)).getStatusCode().value());
    }

    // ---- PATCH -------------------------------------------------------------

    @Test
    void patchMovesClosureAndPassesStatusThrough() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.patchResponse(eq("unavailable_days?id=eq.8"), anyString()))
            .thenReturn(ok(200, "[{\"id\":8}]"));

        ResponseEntity<?> response = run(() -> controller.update(8, """
            {"unavailable_date":"%s","created_by":99,"override_by":99,"affected_reservation_ids":[1]}
            """.formatted(future.plusDays(1)), adminRequest));

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).patchResponse(eq("unavailable_days?id=eq.8"), sent.capture());
        assertEquals(json("{\"unavailable_date\":\"" + future.plusDays(1) + "\"}"), json(sent.getValue()));
    }

    @Test
    void patchOfMissingClosureIs404WithoutWrite() {
        when(supabase.get(contains("unavailable_days?id=eq.404"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.update(404, "{\"reason\":\"x\"}", adminRequest));

        assertEquals(404, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        verify(supabase, never()).postResponse(anyString(), anyString());
    }

    @Test
    void patchThatMatchesNoRowIs404() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.patchResponse(eq("unavailable_days?id=eq.8"), anyString())).thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.update(8, "{\"reason\":\"x\"}", adminRequest));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void patchMovingClosureIntoThePastIs400WithoutWrite() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));

        ResponseEntity<?> response = run(() -> controller.update(
            8, "{\"unavailable_date\":\"" + past + "\"}", adminRequest
        ));

        assertEquals(400, response.getStatusCode().value());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    @Test
    void reasonEditOnAPastClosureIsAllowed() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, past));
        when(supabase.patchResponse(eq("unavailable_days?id=eq.8"), anyString())).thenReturn(ok(200, "[{\"id\":8}]"));

        ResponseEntity<?> response = run(() -> controller.update(
            8, "{\"unavailable_date\":\"" + past + "\",\"reason\":\"Corrected\"}", adminRequest
        ));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void patchClearingReasonSendsNull() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.patchResponse(eq("unavailable_days?id=eq.8"), anyString())).thenReturn(ok(200, "[{\"id\":8}]"));

        run(() -> controller.update(8, "{\"reason\":\"  \"}", adminRequest));

        verify(supabase).patchResponse("unavailable_days?id=eq.8", "{\"reason\":null}");
    }

    @Test
    void patchWithNoUpdatableFieldsIs400() {
        ResponseEntity<?> response = run(() -> controller.update(8, "{\"created_by\":1}", adminRequest));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(supabase);
    }

    @Test
    void patchConflictMapsBtc02To409() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.patchResponse(eq("unavailable_days?id=eq.8"), anyString())).thenReturn(dbError(
            400, "BTC02", json("{\"date\":\"" + future.plusDays(2) + "\",\"reservations\":[{\"id\":3}]}"), "m"
        ));

        ResponseEntity<?> response = run(() -> controller.update(
            8, "{\"unavailable_date\":\"" + future.plusDays(2) + "\"}", adminRequest
        ));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(3, json(response).path("reservations").get(0).path("id").asInt());
        assertTrue(json(response).path("override_available").asBoolean(), json(response).toString());
    }

    @Test
    void emergencyPatchGoesToRpcWithSessionActor() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.postResponse(eq("rpc/update_unavailable_day_with_override"), anyString()))
            .thenReturn(ok(200, "[{\"id\":8}]"));

        ResponseEntity<?> response = run(() -> controller.update(8, """
            {"unavailable_date":"%s","admin_override":true,"override_reason":"HVAC repair",
             "p_actor_user_id":77,"p_unavailable_day_id":1,"override_by":77}
            """.formatted(future.plusDays(3)), adminRequest));

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq("rpc/update_unavailable_day_with_override"), sent.capture());
        verify(supabase, never()).patchResponse(anyString(), anyString());
        assertEquals(json("""
            {"p_actor_user_id":5,"p_reason":"HVAC repair","p_unavailable_day_id":8,
             "p_changes":{"unavailable_date":"%s"}}
            """.formatted(future.plusDays(3))), json(sent.getValue()));
    }

    @Test
    void emergencyPatchWithEmptyRpcResultIs404() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.postResponse(eq("rpc/update_unavailable_day_with_override"), anyString()))
            .thenReturn(ok(200, "[]"));

        ResponseEntity<?> response = run(() -> controller.update(
            8, "{\"reason\":\"x\",\"admin_override\":true,\"override_reason\":\"HVAC\"}", adminRequest
        ));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void emergencyPatchWithoutReasonIs400WithoutWrite() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));

        ResponseEntity<?> response = run(() -> controller.update(
            8, "{\"reason\":\"x\",\"admin_override\":true}", adminRequest
        ));

        assertEquals(400, response.getStatusCode().value());
        verify(supabase, never()).postResponse(anyString(), anyString());
        verify(supabase, never()).patchResponse(anyString(), anyString());
    }

    // ---- DELETE ------------------------------------------------------------

    @Test
    void deleteOfMissingClosureIs404WithoutDelete() {
        when(supabase.get(contains("unavailable_days?id=eq.404"))).thenReturn("[]");

        ResponseEntity<?> response = run(() -> controller.delete(404));

        assertEquals(404, response.getStatusCode().value());
        verify(supabase, never()).delete(anyString());
    }

    @Test
    void deleteSucceedsWith204AndFailureIsReported() {
        when(supabase.get(contains("unavailable_days?id=eq.8"))).thenReturn(storedClosure(8, future));
        when(supabase.delete("unavailable_days?id=eq.8"))
            .thenReturn(ok(204, ""))
            .thenReturn(new SupabaseClient.SupabaseResponse(500, "{\"code\":\"XX000\"}"));

        assertEquals(204, run(() -> controller.delete(8)).getStatusCode().value());
        assertEquals(502, run(() -> controller.delete(8)).getStatusCode().value());
    }
}
