package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import com.reservation.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;

/**
 * Admin follow-up after a confirmed closure: see which cancelled reservations still need a review,
 * record that a review happened, write to the host, and queue failed emails again.
 *
 * <p>Nothing here sends email. Notices are written by the database in the same transaction as the
 * change that causes them, and {@code ClosureNotificationDispatcher} delivers them later.
 *
 * <p>These rows hold private reservation details, so each handler checks for an admin itself as
 * well as relying on {@code AuthInterceptor}.
 */
@RestController
@RequestMapping("/api/admin")
public class ClosureReviewController {

    private static final Set<String> REVIEW_FILTERS = Set.of("pending", "reviewed");
    private static final Set<String> NOTIFICATION_FILTERS =
        Set.of("pending", "sending", "sent", "failed", "skipped");
    // No addresses or message text: delivery state only.
    private static final String NOTIFICATION_COLUMNS =
        "id,kind,status,review_id,batch_id,recipient_user_id,attempts,next_attempt_at,last_error," +
            "sent_via,sent_at,created_at";
    private static final String REVIEW_COLUMNS = "*,closure_notifications(" + NOTIFICATION_COLUMNS + ")";

    private final SupabaseClient supabase;
    private final AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClosureReviewController(SupabaseClient supabase, AuthService authService) {
        this.supabase = supabase;
        this.authService = authService;
    }

    @GetMapping("/closure-reviews")
    public ResponseEntity<String> getReviews(
        @RequestParam(defaultValue = "pending") String status,
        HttpServletRequest request
    ) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        if (!"all".equals(status) && !REVIEW_FILTERS.contains(status)) {
            return ReservationRuleErrors.jsonError(400, "status must be pending, reviewed, or all.");
        }

        String filter = "all".equals(status) ? "" : "review_status=eq." + status + "&";

        return json(supabase.get("closure_reviews?" + filter + "select=" + REVIEW_COLUMNS + "&order=canceled_at.desc,id.desc"));
    }

    @GetMapping("/closure-reviews/{id}")
    public ResponseEntity<String> getReview(@PathVariable long id, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        JsonNode rows = mapper.readTree(supabase.get("closure_reviews?id=eq." + id + "&select=" + REVIEW_COLUMNS));

        return rows.isEmpty()
            ? ReservationRuleErrors.jsonError(404, "Review not found.")
            : json(rows.get(0).toString());
    }

    @PostMapping("/closure-reviews/{id}/review")
    public ResponseEntity<String> markReviewed(
        @PathVariable long id,
        @RequestBody(required = false) String body,
        HttpServletRequest request
    ) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        ObjectNode call = reviewCall(id, request);
        JsonNode note = parse(body).path("note");

        if (!note.isMissingNode() && !note.isNull() && !note.isTextual()) {
            throw new IllegalArgumentException("note must be text.");
        }

        call.put("p_note", note.isTextual() ? note.asText() : null);

        return fromRpc(supabase.postResponse("rpc/mark_closure_review", call.toString()));
    }

    @PostMapping("/closure-reviews/{id}/follow-up")
    public ResponseEntity<String> followUp(
        @PathVariable long id,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        JsonNode message = parse(body).path("message");

        if (!message.isTextual() || message.asText().isBlank()) {
            throw new IllegalArgumentException("message is required.");
        }

        ObjectNode call = reviewCall(id, request);
        call.put("p_message", message.asText());

        return fromRpc(supabase.postResponse("rpc/queue_closure_follow_up", call.toString()));
    }

    @GetMapping("/closure-notifications")
    public ResponseEntity<String> getNotifications(
        @RequestParam(required = false) String status,
        HttpServletRequest request
    ) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        if (status != null && !NOTIFICATION_FILTERS.contains(status)) {
            return ReservationRuleErrors.jsonError(400, "status is not valid.");
        }

        String filter = status == null ? "" : "status=eq." + status + "&";

        return json(supabase.get("closure_notifications?" + filter + "select=" + NOTIFICATION_COLUMNS + "&order=id.desc"));
    }

    // Only a failed row, so a notice that was already sent can never go out twice.
    @PostMapping("/closure-notifications/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable long id, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        ObjectNode changes = mapper.createObjectNode();
        changes.put("status", "pending");
        changes.put("attempts", 0);
        changes.put("next_attempt_at", Instant.now().toString());
        changes.putNull("last_error");

        SupabaseClient.SupabaseResponse response = supabase.patchResponse(
            "closure_notifications?id=eq." + id + "&status=eq.failed&select=id", changes.toString()
        );

        if (!response.isSuccessful()) {
            return ReservationRuleErrors.jsonError(502, "The database could not complete this request.");
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("requeued", mapper.readTree(response.body()).size());

        return json(result.toString());
    }

    @GetMapping("/special-reservations")
    public ResponseEntity<String> getSpecialReservations(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return forbidden();
        }

        return json(supabase.get("reservation_overrides?select=*&order=created_at.desc,id.desc"));
    }

    private boolean isAdmin(HttpServletRequest request) {
        return request.getAttribute("currentUser") instanceof JsonNode user && authService.isAdmin(user);
    }

    private ResponseEntity<String> forbidden() {
        return ReservationRuleErrors.jsonError(403, "Admin access required");
    }

    // The acting admin comes from the session; the database checks that account again.
    private ObjectNode reviewCall(long id, HttpServletRequest request) {
        ObjectNode call = mapper.createObjectNode();
        call.put("p_actor_user_id", ((JsonNode) request.getAttribute("currentUser")).path("id").asLong());
        call.put("p_review_id", id);

        return call;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }

        try {
            JsonNode input = mapper.readTree(body);

            if (input != null && input.isObject()) {
                return input;
            }
        } catch (Exception ignored) {
            // Reported below
        }

        throw new IllegalArgumentException("Request body must be a JSON object.");
    }

    private ResponseEntity<String> fromRpc(SupabaseClient.SupabaseResponse response) {
        return response.isSuccessful() ? json(response.body()) : ReservationRuleErrors.toResponse(response);
    }

    private ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
