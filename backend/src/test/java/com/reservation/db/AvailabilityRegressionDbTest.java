package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** The rules from reservation_availability.sql still hold once the new script is applied. */
@Testcontainers(disabledWithoutDocker = true)
class AvailabilityRegressionDbTest extends AvailabilityDbTestBase {

    private static final String INSERT =
        "INSERT INTO public.events (host_user_id, start_time, end_time, event_type, description, title, department, " +
            "is_public) VALUES (2, ?::timestamptz, ?::timestamptz, 'Meeting', 'd', 't', 'Math', true)";

    private JsonNode settings() throws Exception {
        return MAPPER.readTree(text("SELECT public.get_reservation_availability()::text"));
    }

    @Test
    void savingCreatesClosureEventsAndBumpsTheVersion() throws Exception {
        long before = version();

        JsonNode saved = close("{}", day);

        assertEquals(day.toString(), saved.path("invalid_dates").path(0).asText());
        assertTrue(saved.path("version").asLong() > before);
        assertEquals(1, count("SELECT count(*) FROM public.events WHERE is_closure"));
        assertEquals("Unavailable", text("SELECT title FROM public.events WHERE is_closure"));
    }

    @Test
    void closedDatesAndDatesAfterTheCutoffRefuseBookings() throws Exception {
        close("{}", day);

        fails("23514", () -> exec(INSERT, at(day, 9, 0), at(day, 10, 0)));
        fails("23514", () -> exec(INSERT, at(cutoff.plusDays(1), 9, 0), at(cutoff.plusDays(1), 10, 0)));
        exec(INSERT, at(day.plusDays(1), 9, 0), at(day.plusDays(1), 10, 0));
    }

    @Test
    void anUnconfiguredCalendarRefusesBookings() throws Exception {
        exec("UPDATE public.reservation_availability SET last_bookable_date = NULL");

        fails("23514", () -> exec(INSERT, at(day, 9, 0), at(day, 10, 0)));
    }

    @Test
    void overlappingReservationsAreRefusedAndAdjacentOnesAreNot() throws Exception {
        event(day, 9, 11);

        fails("23P01", () -> event(day, 10, 12));
        event(day, 11, 12);
    }

    @Test
    void reopeningADateRemovesItsClosureAndAllowsBookings() throws Exception {
        close("{}", day);

        close("{}");

        assertEquals(0, settings().path("invalid_dates").size());
        exec(INSERT, at(day, 9, 0), at(day, 10, 0));
    }

    @Test
    void reopeningADateDoesNotBringBackACancelledReservation() throws Exception {
        int id = event(day, 9, 10);
        close(ids(id), day);

        close("{}");

        assertFalse(eventExists(id));
    }

    @Test
    void aPastReservationIsNeverCancelled() throws Exception {
        LocalDate yesterday = today.minusDays(1);
        int id = event(yesterday, 9, 10);

        close("{}", today.plusDays(1));

        assertTrue(eventExists(id));
        assertEquals(0, count("SELECT count(*) FROM public.closure_notifications"));
    }

    @Test
    void invalidSettingsAreRefused() throws Exception {
        fails("22023", () -> save(today.minusDays(1), "{}", version(), "{}", ADMIN, null));
        fails("22023", () -> save(cutoff, dates(cutoff.plusDays(1)), version(), "{}", ADMIN, null));
    }

    @Test
    void closuresCannotBeCheckedInto() throws Exception {
        close("{}", day);
        long closure = count("SELECT id FROM public.events WHERE is_closure");

        fails("23514", () -> exec("INSERT INTO public.attendees (event_id, first_name) VALUES (?, 'Grace')", (int) closure));
    }
}
