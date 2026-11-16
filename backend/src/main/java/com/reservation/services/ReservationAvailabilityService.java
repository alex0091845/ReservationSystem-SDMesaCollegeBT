package com.reservation.services;

import com.reservation.config.SupabaseClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

@Service
public class ReservationAvailabilityService {
    public static final ZoneId TIME_ZONE = ZoneId.of("America/Los_Angeles");
    private static final String READ_ENDPOINT = "rpc/get_reservation_availability";
    private static final String SAVE_ENDPOINT = "rpc/save_reservation_availability";
    private final SupabaseClient supabase;
    private final ObjectMapper mapper;

    public record Availability(LocalDate lastBookableDate, Set<LocalDate> invalidDates, long version) {}

    public static class CancellationConfirmationRequired extends RuntimeException {
        private final JsonNode reservations;

        public CancellationConfirmationRequired(JsonNode reservations) {
            super("Review the existing reservations that will be cancelled before saving.");
            this.reservations = reservations;
        }

        public JsonNode reservations() { return reservations; }
    }

    public ReservationAvailabilityService(SupabaseClient supabase, ObjectMapper mapper) {
        this.supabase = supabase;
        this.mapper = mapper;
    }

    public Availability get() {
        try {
            return read(mapper.readTree(supabase.get(READ_ENDPOINT)), false);
        } catch (Exception error) {
            throw unavailable();
        }
    }

    public Availability save(JsonNode input) {
        Availability requested = read(input, true);
        ObjectNode update = mapper.createObjectNode();
        update.put("p_last_bookable_date", requested.lastBookableDate().toString());
        var dates = update.putArray("p_invalid_dates");
        requested.invalidDates().stream().sorted().forEach(date -> dates.add(date.toString()));
        update.put("p_version", requested.version());
        var confirmed = update.putArray("p_confirmed_cancellations");
        JsonNode confirmations = input.path("confirmed_cancellations");
        if (!confirmations.isMissingNode()) {
            if (!confirmations.isArray()) throw new IllegalArgumentException("Confirmed cancellations must be an array of reservation IDs.");
            for (JsonNode id : confirmations) {
                if (!id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() < 1) {
                    throw new IllegalArgumentException("Confirmed cancellations must contain valid reservation IDs.");
                }
                confirmed.add(id.asInt());
            }
        }
        SupabaseClient.SupabaseResponse response;
        try {
            response = supabase.postResponse(SAVE_ENDPOINT, update.toString());
        } catch (Exception error) {
            throw unavailable();
        }
        try {
            JsonNode result = mapper.readTree(response.body());
            if (!response.isSuccessful() && "40001".equals(result.path("code").asText())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another admin changed availability. Reload it before saving again.");
            }
            if (!response.isSuccessful()) throw unavailable();
            if (result.path("requires_confirmation").asBoolean(false)) {
                JsonNode reservations = result.path("reservations");
                if (!reservations.isArray() || reservations.isEmpty()) throw unavailable();
                throw new CancellationConfirmationRequired(reservations);
            }
            return read(result, false);
        } catch (CancellationConfirmationRequired error) {
            throw error;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable();
        }
    }

    public void validateDates(Collection<LocalDate> dates) {
        Availability settings = get();
        if (settings.lastBookableDate() == null) {
            throw new IllegalArgumentException("Reservations are closed until an admin sets the last bookable date.");
        }
        for (LocalDate date : dates) {
            if (date.isAfter(settings.lastBookableDate())) {
                throw new IllegalArgumentException("Reservations must be on or before " + settings.lastBookableDate() + ".");
            }
            if (settings.invalidDates().contains(date)) {
                throw new IllegalArgumentException("Reservations are not available on " + date + ".");
            }
        }
    }

    public ObjectNode toJson(Availability settings) {
        ObjectNode json = mapper.createObjectNode();
        if (settings.lastBookableDate() == null) {
            json.putNull("last_bookable_date");
        } else {
            json.put("last_bookable_date", settings.lastBookableDate().toString());
        }
        var dates = json.putArray("invalid_dates");
        settings.invalidDates().stream().sorted().forEach(date -> dates.add(date.toString()));
        json.put("version", settings.version());
        return json;
    }

    private Availability read(JsonNode input, boolean saving) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("Availability must be a JSON object.");
        }
        JsonNode cutoff = input.path("last_bookable_date");
        LocalDate lastDate = cutoff.isNull() && !saving ? null : parseDate(cutoff);
        if (saving && lastDate.isBefore(LocalDate.now(TIME_ZONE))) {
            throw new IllegalArgumentException("The last bookable date cannot be in the past.");
        }
        JsonNode dates = input.path("invalid_dates");
        if (!dates.isArray()) {
            throw new IllegalArgumentException("Invalid dates must be an array of dates.");
        }
        Set<LocalDate> invalidDates = new TreeSet<>();
        for (JsonNode value : dates) {
            LocalDate date = parseDate(value);
            if (lastDate == null || date.isAfter(lastDate)) {
                throw new IllegalArgumentException("Invalid dates must be on or before the last bookable date.");
            }
            invalidDates.add(date);
        }
        JsonNode version = input.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0 || version.asLong() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("A valid availability version is required.");
        }
        return new Availability(lastDate, Set.copyOf(invalidDates), version.asLong());
    }

    private LocalDate parseDate(JsonNode value) {
        try {
            if (!value.isTextual() || !value.asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new DateTimeParseException("Invalid date", "", 0);
            }
            return LocalDate.parse(value.asText());
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Dates must be valid calendar dates in YYYY-MM-DD format.");
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Reservation availability could not be loaded or saved. Please try again or ask an admin to check the setup.");
    }
}
