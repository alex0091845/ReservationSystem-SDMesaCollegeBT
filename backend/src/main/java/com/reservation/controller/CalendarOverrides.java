package com.reservation.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the explicit admin override that lets a request bypass a calendar rule:
 * {@code "admin_override": true} plus an {@code "override_reason"}.
 *
 * <p>Reservations use it to book closed days or dates outside a semester; unavailable
 * days use it to close a day that already has reservations. Callers must check that the
 * caller is an admin before honouring it, and the database checks again.
 */
final class CalendarOverrides {

    static final int MAX_REASON_LENGTH = 500;

    private CalendarOverrides() {
    }

    static boolean requested(JsonNode input) {
        JsonNode flag = input.get("admin_override");

        if (flag == null || flag.isNull()) {
            return false;
        }

        if (!flag.isBoolean()) {
            throw new IllegalArgumentException("admin_override must be true or false.");
        }

        return flag.asBoolean();
    }

    // Returns the trimmed override reason, or null when no override was requested.
    static String reason(JsonNode input) {
        if (!requested(input)) {
            return null;
        }

        JsonNode reason = input.get("override_reason");

        if (reason == null || !reason.isTextual() || reason.asText().isBlank()) {
            throw new IllegalArgumentException("override_reason is required when admin_override is true.");
        }

        if (reason.asText().trim().length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                "override_reason must be " + MAX_REASON_LENGTH + " characters or fewer."
            );
        }

        return reason.asText().trim();
    }

    // Arguments shared by the override RPC functions. The acting admin always comes
    // from the server-side session user; the database checks that account again.
    static ObjectNode rpcCall(ObjectMapper mapper, JsonNode currentUser, String reason) {
        ObjectNode call = mapper.createObjectNode();
        call.set("p_actor_user_id", currentUser.path("id"));
        call.put("p_reason", reason);

        return call;
    }
}
