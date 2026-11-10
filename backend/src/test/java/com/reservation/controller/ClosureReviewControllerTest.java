package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
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

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class ClosureReviewControllerTest {

    private static final String REVIEW_RPC = "rpc/mark_closure_review";
    private static final String FOLLOW_UP_RPC = "rpc/queue_closure_follow_up";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SupabaseClient supabase = mock(SupabaseClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ClosureReviewController controller = new ClosureReviewController(supabase, auth);

    @BeforeEach
    void signedInAsAdmin() {
        when(request.getAttribute("currentUser")).thenReturn(mapper.readTree("{\"id\":5,\"role_name\":\"admin\"}"));
        when(auth.isAdmin(any())).thenReturn(true);
        when(supabase.get(anyString())).thenReturn("[]");
        when(supabase.postResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "{\"id\":8}"));
    }

    private String lastQuery() {
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(supabase).get(query.capture());
        return query.getValue();
    }

    private JsonNode rpcBody(String endpoint) {
        ArgumentCaptor<String> call = ArgumentCaptor.forClass(String.class);
        verify(supabase).postResponse(eq(endpoint), call.capture());
        return mapper.readTree(call.getValue());
    }

    // ---- Who can use this ------------------------------------------------------------

    private Stream<Supplier<ResponseEntity<String>>> everyEndpoint() {
        return Stream.of(
            () -> controller.getReviews("pending", request),
            () -> controller.getReview(8, request),
            () -> controller.markReviewed(8, "{}", request),
            () -> controller.followUp(8, "{\"message\":\"Hi\"}", request),
            () -> controller.getNotifications(null, request),
            () -> controller.retry(8, request),
            () -> controller.getSpecialReservations(request)
        );
    }

    @Test
    void everyEndpointRefusesFacultyItselfAndTouchesNothing() {
        when(request.getAttribute("currentUser")).thenReturn(mapper.readTree("{\"id\":2,\"role_name\":\"faculty\"}"));
        when(auth.isAdmin(any())).thenReturn(false);

        everyEndpoint().forEach(call -> assertEquals(403, call.get().getStatusCode().value()));

        verifyNoInteractions(supabase);
    }

    @Test
    void everyEndpointRefusesARequestWithNoSession() {
        when(request.getAttribute("currentUser")).thenReturn(null);

        everyEndpoint().forEach(call -> assertEquals(403, call.get().getStatusCode().value()));

        verifyNoInteractions(supabase);
    }

    // ---- Lists -----------------------------------------------------------------------

    @Test
    void theDefaultListIsWhatStillNeedsFollowUp() {
        assertEquals(200, controller.getReviews("pending", request).getStatusCode().value());

        assertTrue(lastQuery().startsWith("closure_reviews?review_status=eq.pending&select="));
    }

    @Test
    void allReviewsCanBeListed() {
        controller.getReviews("all", request);

        assertTrue(lastQuery().startsWith("closure_reviews?select="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "pending&host_email=eq.a", "PENDING", ""})
    void anUnknownReviewFilterIsRefused(String status) {
        assertEquals(400, controller.getReviews(status, request).getStatusCode().value());

        verifyNoInteractions(supabase);
    }

    @Test
    void reviewsShowDeliveryStateWithoutMessageTextOrAddressesFromTheOutbox() {
        controller.getReviews("all", request);

        String embedded = lastQuery().split("closure_notifications\\(")[1];
        assertTrue(embedded.contains("status"));
        assertFalse(embedded.contains("follow_up_message"));
        assertFalse(embedded.contains("email"));
    }

    @Test
    void anUnknownReviewIs404() {
        assertEquals(404, controller.getReview(9, request).getStatusCode().value());
    }

    @Test
    void oneReviewIsReturnedOnItsOwn() {
        when(supabase.get(startsWith("closure_reviews?id=eq.8&"))).thenReturn("[{\"id\":8}]");

        ResponseEntity<String> response = controller.getReview(8, request);

        assertEquals(8, mapper.readTree(response.getBody()).path("id").asInt());
    }

    @Test
    void notificationsCanBeFilteredByStatusAndCarryNoMessageText() {
        controller.getNotifications("failed", request);

        String query = lastQuery();
        assertTrue(query.startsWith("closure_notifications?status=eq.failed&select="), query);
        assertFalse(query.contains("follow_up_message"));
        assertFalse(query.contains("*"));
    }

    @Test
    void anUnknownNotificationFilterIsRefused() {
        assertEquals(400, controller.getNotifications("x", request).getStatusCode().value());
    }

    @Test
    void specialReservationsShowTheAuditTrail() {
        assertEquals(200, controller.getSpecialReservations(request).getStatusCode().value());

        assertTrue(lastQuery().startsWith("reservation_overrides?"));
    }

    // ---- Review ----------------------------------------------------------------------

    @Test
    void reviewUsesTheSessionAdminAndTheNote() {
        controller.markReviewed(8, "{\"note\":\"Called them\",\"p_actor_user_id\":1}", request);

        JsonNode call = rpcBody(REVIEW_RPC);
        assertEquals(5, call.path("p_actor_user_id").asInt());
        assertEquals(8, call.path("p_review_id").asInt());
        assertEquals("Called them", call.path("p_note").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"note\":null}"})
    void aNoteIsOptional(String body) {
        assertEquals(200, controller.markReviewed(8, body, request).getStatusCode().value());

        assertTrue(rpcBody(REVIEW_RPC).path("p_note").isNull());
    }

    @Test
    void aMissingBodyIsAccepted() {
        assertEquals(200, controller.markReviewed(8, null, request).getStatusCode().value());
    }

    @Test
    void aNonTextNoteIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> controller.markReviewed(8, "{\"note\":5}", request));
        assertThrows(IllegalArgumentException.class, () -> controller.markReviewed(8, "[]", request));
    }

    // ---- Follow-up -------------------------------------------------------------------

    @Test
    void followUpUsesTheSessionAdminAndTheMessage() {
        controller.followUp(8, "{\"message\":\"New room\"}", request);

        JsonNode call = rpcBody(FOLLOW_UP_RPC);
        assertEquals(5, call.path("p_actor_user_id").asInt());
        assertEquals("New room", call.path("p_message").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":\"  \"}", "{\"message\":5}", "not json"})
    void aFollowUpNeedsAMessage(String body) {
        assertThrows(IllegalArgumentException.class, () -> controller.followUp(8, body, request));

        verifyNoInteractions(supabase);
    }

    @ParameterizedTest
    @CsvSource({"BTA01,403", "BTN01,404", "BTN02,409", "XX000,502"})
    void databaseRefusalsBecomeTheirOwnResponses(String code, int status) {
        when(supabase.postResponse(eq(FOLLOW_UP_RPC), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(400, "{\"code\":\"" + code + "\",\"message\":\"detail\"}"));

        ResponseEntity<String> response = controller.followUp(8, "{\"message\":\"Hi\"}", request);

        assertEquals(status, response.getStatusCode().value());
        assertFalse(response.getBody().contains("detail"));
    }

    // ---- Retry -----------------------------------------------------------------------

    @Test
    void retryOnlyTouchesAFailedRowAndResetsItsAttempts() {
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[{\"id\":8}]"));

        ResponseEntity<String> response = controller.retry(8, request);

        assertEquals(1, mapper.readTree(response.getBody()).path("requeued").asInt());
        ArgumentCaptor<String> changes = ArgumentCaptor.forClass(String.class);
        verify(supabase).patchResponse(eq("closure_notifications?id=eq.8&status=eq.failed&select=id"), changes.capture());
        JsonNode sent = mapper.readTree(changes.getValue());
        assertEquals("pending", sent.path("status").asText());
        assertEquals(0, sent.path("attempts").asInt());
    }

    @Test
    void retryingANoticeThatDidNotFailRequeuesNothing() {
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(200, "[]"));

        assertEquals(0, mapper.readTree(controller.retry(8, request).getBody()).path("requeued").asInt());
    }

    @Test
    void aDatabaseFailureDuringRetryIsReported() {
        when(supabase.patchResponse(anyString(), anyString()))
            .thenReturn(new SupabaseClient.SupabaseResponse(500, "{}"));

        assertEquals(502, controller.retry(8, request).getStatusCode().value());
    }
}
