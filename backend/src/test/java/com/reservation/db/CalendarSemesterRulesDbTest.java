package com.reservation.db;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Semester constraints and the semesters_guard trigger. */
@Testcontainers(disabledWithoutDocker = true)
class CalendarSemesterRulesDbTest extends CalendarDbTestBase {

    @Test
    void overlappingSemestersAreRefusedEvenOnASingleSharedDay() throws Exception {
        LocalDate start = today.plusDays(10);
        LocalDate end = today.plusDays(60);
        semester("Fall", start, end);

        fails("23P01", () -> semester("Overlap end", end, end.plusDays(30)));
        fails("23P01", () -> semester("Overlap start", start.minusDays(30), start));
        fails("23P01", () -> semester("Inside", start.plusDays(5), start.plusDays(6)));
        semester("Adjacent after", end.plusDays(1), end.plusDays(30));
        semester("Adjacent before", start.minusDays(30), start.minusDays(1));
        assertEquals(3, count("SELECT count(*) FROM public.semesters"));
    }

    @Test
    void namesAreUniqueIgnoringCaseAndSurroundingSpaces() throws Exception {
        semester("Fall 2026", today.plusDays(1), today.plusDays(10));

        fails("23505", () -> semester("  fall 2026 ", today.plusDays(20), today.plusDays(30)));
    }

    @Test
    void dateOrderLengthAndNameChecks() throws Exception {
        fails("23514", () -> semester("Backwards", today.plusDays(10), today.plusDays(9)));
        fails("23514", () -> semester("Too long", today, today.plusDays(366)));
        fails("23514", () -> semester("   ", today, today.plusDays(1)));
        fails("23514", () -> semester("x".repeat(101), today, today.plusDays(1)));
        semester("One year", today, today.plusDays(365));
        semester("One day", today.plusDays(400), today.plusDays(400));
    }

    @Test
    void shrinkingAwayFromAnUpcomingReservationIsRefusedWithDetails() throws Exception {
        LocalDate end = today.plusDays(60);
        long id = semester("Fall", today.plusDays(1), end);
        int reservation = event(end, 10, 11);

        PSQLException error = fails("BTC03",
            () -> exec("UPDATE public.semesters SET end_date = ?::date WHERE id = ?", end.minusDays(1), id));

        JsonNode detail = detail(error);
        assertEquals(id, detail.path("semester_id").asLong());
        assertEquals(1, detail.path("reservations").size());
        assertEquals(reservation, detail.path("reservations").get(0).path("id").asInt());
        assertEquals("Lab", detail.path("reservations").get(0).path("title").asText());
        assertEquals(FACULTY, detail.path("reservations").get(0).path("host_user_id").asInt());
        assertTrue(detail.path("unavailable_days").isArray());
        assertTrue(detail.path("unavailable_days").isEmpty());
        assertEquals(end.toString(), text("SELECT end_date::text FROM public.semesters WHERE id = ?", id));
    }

    @Test
    void movingTheStartPastAReservationIsRefused() throws Exception {
        LocalDate start = today.plusDays(5);
        long id = semester("Fall", start, today.plusDays(60));
        event(start, 10, 11);

        fails("BTC03", () -> exec("UPDATE public.semesters SET start_date = ?::date WHERE id = ?", start.plusDays(1), id));
        fails("BTC03", () -> exec(
            "UPDATE public.semesters SET start_date = ?::date, end_date = ?::date WHERE id = ?",
            today.plusDays(100), today.plusDays(200), id));
    }

    @Test
    void shrinkingAwayFromAClosureIsRefusedWithDetails() throws Exception {
        LocalDate end = today.plusDays(60);
        long id = semester("Fall", today.plusDays(1), end);
        long closure = closure(end);

        PSQLException error = fails("BTC03",
            () -> exec("UPDATE public.semesters SET end_date = ?::date WHERE id = ?", end.minusDays(1), id));

        JsonNode days = detail(error).path("unavailable_days");
        assertEquals(1, days.size());
        assertEquals(closure, days.get(0).path("id").asLong());
        assertEquals(end.toString(), days.get(0).path("unavailable_date").asText());
        assertTrue(detail(error).path("reservations").isEmpty());
    }

    @Test
    void deletingASemesterWithUpcomingReservationsOrClosuresIsRefused() throws Exception {
        long withEvent = semester("A", today.plusDays(1), today.plusDays(30));
        event(today.plusDays(10), 10, 11);
        long withClosure = semester("B", today.plusDays(31), today.plusDays(60));
        closure(today.plusDays(40));
        long empty = semester("C", today.plusDays(61), today.plusDays(90));

        PSQLException a = fails("BTC03", () -> exec("DELETE FROM public.semesters WHERE id = ?", withEvent));
        assertEquals(1, detail(a).path("reservations").size());
        PSQLException b = fails("BTC03", () -> exec("DELETE FROM public.semesters WHERE id = ?", withClosure));
        assertEquals(1, detail(b).path("unavailable_days").size());
        exec("DELETE FROM public.semesters WHERE id = ?", empty);

        assertEquals(2, count("SELECT count(*) FROM public.semesters"));
    }

    @Test
    void harmlessEditsAreAllowedAndTimestampsAreManaged() throws Exception {
        long id = semester("Fall", today.plusDays(1), today.plusDays(60));
        event(today.plusDays(30), 10, 11);
        closure(today.plusDays(31));
        String createdAt = text("SELECT created_at::text FROM public.semesters WHERE id = ?", id);

        exec("UPDATE public.semesters SET name = 'Fall (revised)', created_at = '2000-01-01' WHERE id = ?", id);
        exec("UPDATE public.semesters SET end_date = end_date + 10 WHERE id = ?", id);
        exec("UPDATE public.semesters SET end_date = ?::date, start_date = ?::date WHERE id = ?",
            today.plusDays(31), today.plusDays(30), id);

        assertEquals(createdAt, text("SELECT created_at::text FROM public.semesters WHERE id = ?", id));
        assertEquals("Fall (revised)", text("SELECT name FROM public.semesters WHERE id = ?", id));
    }

    @Test
    void finishedSemesterWithOnlyPastReservationsCanBeDeleted() throws Exception {
        long past = semester("Spring", today.minusDays(200), today.minusDays(100));
        int old = event(today.minusDays(150), 10, 11);

        exec("DELETE FROM public.semesters WHERE id = ?", past);

        assertEquals(0, count("SELECT count(*) FROM public.semesters"));
        assertEquals(1, count("SELECT count(*) FROM public.events WHERE id = ?", old));
    }

    @Test
    void shrinkingAwayFromEmptyDaysIsAllowedButDeletingACoveringSemesterIsNot() throws Exception {
        long first = semester("First", today.plusDays(1), today.plusDays(30));
        long second = semester("Second", today.plusDays(31), today.plusDays(60));
        event(today.plusDays(45), 10, 11);

        exec("UPDATE public.semesters SET end_date = ?::date WHERE id = ?", today.plusDays(20), first);
        fails("BTC03", () -> exec("DELETE FROM public.semesters WHERE id = ?", second));
    }

    @Test
    void semesterChangesTakeTheCalendarLock() throws Exception {
        long id = semester("Fall", today.plusDays(1), today.plusDays(60));
        Connection other = serviceRole();
        other.setAutoCommit(false);
        exec(other, "SELECT public.calendar_lock()");

        db.setAutoCommit(false);
        exec("SET LOCAL lock_timeout = '300ms'");
        PSQLException error = fails("55P03",
            () -> exec("UPDATE public.semesters SET end_date = end_date + 1 WHERE id = ?", id));
        assertNotNull(error);
        db.rollback();
        other.rollback();
        db.setAutoCommit(true);
    }
}
