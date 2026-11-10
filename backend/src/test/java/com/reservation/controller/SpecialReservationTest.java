package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import com.reservation.services.ReservationAvailabilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SpecialReservationTest {

    private static final String RPC = "rpc/create_special_reservation";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ReservationController controller = new ReservationController(
        supabase, mapper, auth, new ReservationAvailabilityService(supabase, mapper)
    );

    @BeforeEach
    void signedInAsAdmin() {
        when(request.getAttribute("currentUser")).thenReturn(mapper.readTree("{\"id\":5,\"role_name\":\"admin\"}"));
        when(auth.isAdmin(any())).thenReturn(true);
        when(supabase.postResponse(eq(RPC), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "{\"reservation\":{\"id\":9},\"override_id\":1}"));
    }

    private ObjectNode body() {
        ObjectNode body = mapper.createObjectNode();
        body.put("override_unavailable_date", true);
        body.put("override_reason", "Accreditation visit");
        body.set("event", mapper.readTree("""
            {"host_user_id":2,"start_time":"2099-01-05T17:00:00Z","end_time":"2099-01-05T18:00:00Z",
             "event_type":"Meeting","description":"d","title":"Visit","department":"Math","is_public":false,
             "is_closure":false,"id":77,"recurrence_group_id":"g1"}
            """));
        return body;
    }

    private ResponseEntity<String> create(JsonNode body) {
        return controller.createSpecial(body.toString(), request);
    }

    private void refuses(int expected, ResponseEntity<String> response) {
        assertEquals(expected, response.getStatusCode().value(), response.getBody());
        verify(supabase, never()).postResponse(eq(RPC), anyString());
    }

    @Test
    void adminRequestReachesTheDatabaseWithTheSessionUserAsActor() {
        ResponseEntity<String> response = create(body());

        assertEquals(201, response.getStatusCode().value());
        ArgumentCaptor<String> call = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq(RPC), call.capture());
        JsonNode sent = mapper.readTree(call.getValue());
        assertEquals(5, sent.path("p_actor_user_id").asInt());
        assertEquals("Accreditation visit", sent.path("p_reason").asText());
        assertEquals("Visit", sent.path("p_event").path("title").asText());
    }

    @Test
    void theActorCannotBeChosenByTheBody() {
        ObjectNode body = body();
        body.put("p_actor_user_id", 1);
        body.put("actor_user_id", 1);

        create(body);

        ArgumentCaptor<String> call = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq(RPC), call.capture());
        assertEquals(5, mapper.readTree(call.getValue()).path("p_actor_user_id").asInt());
    }

    @Test
    void onlyTheKnownEventFieldsAreForwarded() {
        create(body());

        ArgumentCaptor<String> call = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq(RPC), call.capture());
        JsonNode event = mapper.readTree(call.getValue()).path("p_event");
        assertFalse(event.has("id"));
        assertFalse(event.has("is_closure"));
    }

    @Test
    void facultyIsRefusedBeforeAnythingIsRead() {
        when(request.getAttribute("currentUser")).thenReturn(mapper.readTree("{\"id\":2,\"role_name\":\"faculty\"}"));
        when(auth.isAdmin(any())).thenReturn(false);

        refuses(403, create(body()));
    }

    @Test
    void noSessionIsRefused() {
        when(request.getAttribute("currentUser")).thenReturn(null);
        when(auth.isAdmin(any())).thenReturn(false);

        refuses(403, create(body()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "\"true\"", "1"})
    void theOverrideMustBeExplicitlyRequested(String flag) {
        ObjectNode body = body();
        body.set("override_unavailable_date", mapper.readTree(flag));

        refuses(400, create(body));
    }

    @Test
    void aMissingFlagIsRefused() {
        ObjectNode body = body();
        body.remove("override_unavailable_date");

        refuses(400, create(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null", "5"})
    void aReasonIsRequired(String reason) {
        ObjectNode body = body();
        body.set("override_reason", mapper.readTree(reason));

        refuses(400, create(body));
    }

    @Test
    void anOverlongReasonIsRefused() {
        ObjectNode body = body();
        body.put("override_reason", "x".repeat(501));

        refuses(400, create(body));
    }

    @Test
    void theNormalReservationRulesStillApply() {
        ObjectNode late = body();
        ((ObjectNode) late.path("event")).put("end_time", "2099-01-06T03:00:00Z");
        refuses(400, create(late));

        ObjectNode past = body();
        ((ObjectNode) past.path("event")).put("start_time", "2001-01-05T17:00:00Z");
        ((ObjectNode) past.path("event")).put("end_time", "2001-01-05T18:00:00Z");
        refuses(400, create(past));

        ObjectNode closure = body();
        ((ObjectNode) closure.path("event")).put("event_type", "Unavailable");
        refuses(400, create(closure));

        ObjectNode missing = body();
        missing.remove("event");
        refuses(400, create(missing));
    }

    @Test
    void aMalformedBodyIsRefused() {
        assertThrows(RuntimeException.class, () -> controller.createSpecial("not json", request));
        verify(supabase, never()).postResponse(eq(RPC), anyString());
    }

    @ParameterizedTest
    @CsvSource({
        "BTA01,403", "BTS01,409", "BTS02,409", "23P01,409", "40P01,409", "23503,400", "22023,400", "XX000,502"
    })
    void databaseRefusalsBecomeTheirOwnResponses(String code, int status) {
        when(supabase.postResponse(eq(RPC), anyString())).thenReturn(new SupabaseClient.SupabaseResponse(
            400, "{\"code\":\"" + code + "\",\"message\":\"secret detail\"}"
        ));

        ResponseEntity<String> response = create(body());

        assertEquals(status, response.getStatusCode().value());
        assertFalse(response.getBody().contains("secret detail"));
    }

    @Test
    void theNormalCreateRouteIgnoresTheOverrideFields() {
        when(supabase.get("rpc/get_reservation_availability")).thenReturn(
            "{\"last_bookable_date\":\"2099-12-31\",\"invalid_dates\":[\"2099-01-05\"],\"version\":1}"
        );
        ObjectNode event = (ObjectNode) body().path("event");
        event.put("override_unavailable_date", true);
        event.put("override_reason", "Please");

        ResponseEntity<String> response = controller.create(event.toString(), request);

        assertEquals(400, response.getStatusCode().value());
        verify(supabase, never()).postResponse(eq("events"), anyString());
    }
}
