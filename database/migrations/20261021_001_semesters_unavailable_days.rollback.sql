-- =============================================================================
-- Rollback for migration 20261021_001: semesters, unavailable days, and
-- calendar rules
-- =============================================================================
--
-- Removes everything the migration added and restores the previous behaviour
-- (no semester or closed-day checks on reservations).
--
-- WARNING: this permanently deletes every semester, every unavailable day,
-- and the calendar override audit trail. Export them first if they matter.
-- Reservations (public.events) are not touched.
-- =============================================================================

BEGIN;

DROP TRIGGER IF EXISTS events_calendar_rules_on_update ON public.events;
DROP TRIGGER IF EXISTS events_calendar_rules_on_insert ON public.events;
DROP TRIGGER IF EXISTS events_calendar_lock ON public.events;

DROP FUNCTION IF EXISTS public.update_unavailable_day_with_override(integer, text, bigint, jsonb);
DROP FUNCTION IF EXISTS public.create_unavailable_day_with_override(integer, text, jsonb);
DROP FUNCTION IF EXISTS public.update_event_with_calendar_override(integer, text, integer, jsonb);
DROP FUNCTION IF EXISTS public.create_events_with_calendar_override(integer, text, jsonb);

-- Dropping the tables also drops their triggers and policies.
DROP TABLE IF EXISTS public.calendar_override_audit;
DROP TABLE IF EXISTS public.unavailable_days;
DROP TABLE IF EXISTS public.semesters;

DROP FUNCTION IF EXISTS public.semesters_guard();
DROP FUNCTION IF EXISTS public.unavailable_days_guard();
DROP FUNCTION IF EXISTS public.events_enforce_calendar_rules();
DROP FUNCTION IF EXISTS public.calendar_override_admin();
DROP FUNCTION IF EXISTS public.calendar_end_override();
DROP FUNCTION IF EXISTS public.calendar_begin_override(integer, text);
DROP FUNCTION IF EXISTS public.calendar_event_violations(integer[]);
DROP FUNCTION IF EXISTS public.calendar_lock_statement();
DROP FUNCTION IF EXISTS public.calendar_lock();
DROP FUNCTION IF EXISTS public.calendar_event_dates(timestamp with time zone, timestamp with time zone);
DROP FUNCTION IF EXISTS public.calendar_local_date(timestamp with time zone);

NOTIFY pgrst, 'reload schema';

COMMIT;
