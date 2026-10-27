-- =============================================================================
-- Migration 20261021_001: semesters, unavailable days, and calendar rules
-- =============================================================================
--
-- Adds semester boundaries and whole-day closures for BT-216, and makes the
-- database refuse any reservation write that breaks them.
--
-- * Additive only: three new tables, helper functions, triggers, and two RPC
--   functions. No existing row is modified or deleted.
-- * Runs in one transaction. Any error leaves the database unchanged.
-- * Written for PostgreSQL 15+ (verified on 16), as used by Supabase.
--
-- BEFORE APPLYING, read database/README.md. In particular: once this runs,
-- every new or moved reservation must fall inside a semester, so create the
-- current semester(s) in the same maintenance window or bookings will be
-- refused.
--
-- Error codes raised here (the backend maps them to HTTP responses):
--   BTC01  reservation is on an unavailable day or outside every semester  409
--   BTC02  unavailable day collides with existing reservations (no override) 409
--   BTC03  semester change would strand reservations or unavailable days   409
--   BTC04  unavailable day is not inside a (matching) semester             409
--   BTC05  unavailable day is in the past                                  400
--   BTA01  calendar override by someone who is not an enabled admin        403
-- Each of them carries a JSON object in the error DETAIL.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. Tables
-- -----------------------------------------------------------------------------

CREATE TABLE public.semesters (
  id bigint GENERATED ALWAYS AS IDENTITY,
  name text NOT NULL,
  start_date date NOT NULL,
  end_date date NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT semesters_pkey PRIMARY KEY (id),
  CONSTRAINT semesters_name_check CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
  CONSTRAINT semesters_date_order_check CHECK (start_date <= end_date),
  -- Guards against a typo such as 2026-08-24 to 2062-12-19 quietly opening
  -- decades of bookings.
  CONSTRAINT semesters_length_check CHECK (end_date - start_date <= 365),
  -- Boundaries are inclusive ('[]'). No two semesters may share a day, so
  -- "which semester is this date in?" always has at most one answer.
  CONSTRAINT semesters_no_overlap EXCLUDE USING gist (daterange(start_date, end_date, '[]') WITH &&)
);

CREATE UNIQUE INDEX semesters_name_unique_idx ON public.semesters (lower(btrim(name)));

CREATE TABLE public.unavailable_days (
  id bigint GENERATED ALWAYS AS IDENTITY,
  -- Filled in from unavailable_date by unavailable_days_guard() when omitted.
  semester_id bigint NOT NULL,
  -- A whole local day in America/Los_Angeles.
  unavailable_date date NOT NULL,
  reason text,
  created_by integer,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT unavailable_days_pkey PRIMARY KEY (id),
  CONSTRAINT unavailable_days_date_key UNIQUE (unavailable_date),
  CONSTRAINT unavailable_days_semester_id_fkey FOREIGN KEY (semester_id)
    REFERENCES public.semesters(id) ON DELETE RESTRICT,
  CONSTRAINT unavailable_days_created_by_fkey FOREIGN KEY (created_by)
    REFERENCES public.users(id) ON DELETE SET NULL,
  CONSTRAINT unavailable_days_reason_check CHECK (reason IS NULL OR char_length(reason) <= 200)
);

CREATE INDEX unavailable_days_semester_id_idx ON public.unavailable_days (semester_id);

-- Append-only history of admin overrides, one row per reservation involved:
--   create / update  a reservation saved on a restricted date;
--   close_day        a reservation that already existed on a day an admin
--                    closed anyway (emergency or maintenance closure).
-- The backend can add rows but never edit or remove them.
CREATE TABLE public.calendar_override_audit (
  id bigint GENERATED ALWAYS AS IDENTITY,
  event_id integer,
  action text NOT NULL,
  actor_user_id integer,
  -- Kept as text so the trail survives the admin account being deleted.
  actor_email text NOT NULL,
  reason text NOT NULL,
  overridden_rules text[] NOT NULL,
  restricted_dates date[] NOT NULL,
  event_start_time timestamp with time zone NOT NULL,
  event_end_time timestamp with time zone NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT calendar_override_audit_pkey PRIMARY KEY (id),
  CONSTRAINT calendar_override_audit_event_id_fkey FOREIGN KEY (event_id)
    REFERENCES public.events(id) ON DELETE SET NULL,
  CONSTRAINT calendar_override_audit_actor_user_id_fkey FOREIGN KEY (actor_user_id)
    REFERENCES public.users(id) ON DELETE SET NULL,
  CONSTRAINT calendar_override_audit_action_check CHECK (action IN ('create', 'update', 'close_day')),
  CONSTRAINT calendar_override_audit_reason_check CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX calendar_override_audit_event_id_idx ON public.calendar_override_audit (event_id);

-- -----------------------------------------------------------------------------
-- 2. Helpers
-- -----------------------------------------------------------------------------

-- BT-216 runs on Los Angeles local dates, including across DST changes.
CREATE FUNCTION public.calendar_local_date(p_instant timestamp with time zone)
RETURNS date
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog
AS $$
  SELECT (p_instant AT TIME ZONE 'America/Los_Angeles')::date
$$;

-- Every local date a reservation touches. The end instant is exclusive, so a
-- booking that ends exactly at midnight does not claim the following day.
CREATE FUNCTION public.calendar_event_dates(
  p_start timestamp with time zone,
  p_end timestamp with time zone
)
RETURNS SETOF date
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog
AS $$
  SELECT day::date
  FROM generate_series(
    public.calendar_local_date(p_start)::timestamp,
    greatest(
      public.calendar_local_date(p_start),
      public.calendar_local_date(p_end - interval '1 microsecond')
    )::timestamp,
    interval '1 day'
  ) AS day
$$;

-- Serializes every write that could break a calendar rule: placing or moving
-- a reservation, adding or moving an unavailable day, and changing or removing
-- a semester. Each of those takes this lock before checking the other tables
-- and holds it until commit, so for example a reservation and a closure for
-- the same day can never both commit.
--
-- The checks need READ COMMITTED (PostgREST's and the SQL Editor's default) so
-- that each one sees rows committed while it waited. REPEATABLE READ and
-- SERIALIZABLE take their snapshot before the wait, and SERIALIZABLE only
-- catches the race when every writer is SERIALIZABLE, so both are refused.
CREATE FUNCTION public.calendar_lock()
RETURNS void
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'Calendar writes must run under READ COMMITTED isolation.'
      USING ERRCODE = '0A000';
  END IF;

  PERFORM pg_advisory_xact_lock(216, 1);
END
$$;

CREATE FUNCTION public.calendar_lock_statement()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
  PERFORM public.calendar_lock();
  RETURN NULL;
END
$$;

-- One row per (reservation, local date) that breaks a calendar rule.
-- VOLATILE on purpose: it must read rows committed while the caller waited
-- for calendar_lock().
CREATE FUNCTION public.calendar_event_violations(p_event_ids integer[])
RETURNS TABLE (
  event_id integer,
  local_date date,
  is_unavailable boolean,
  is_outside_semester boolean
)
LANGUAGE sql VOLATILE
SET search_path = pg_catalog
AS $$
  SELECT checked.event_id, checked.local_date, checked.is_unavailable, checked.is_outside_semester
  FROM (
    SELECT
      e.id AS event_id,
      d.local_date,
      EXISTS (
        SELECT 1 FROM public.unavailable_days u WHERE u.unavailable_date = d.local_date
      ) AS is_unavailable,
      NOT EXISTS (
        SELECT 1 FROM public.semesters s WHERE d.local_date BETWEEN s.start_date AND s.end_date
      ) AS is_outside_semester
    FROM public.events e
    CROSS JOIN LATERAL public.calendar_event_dates(e.start_time, e.end_time) AS d(local_date)
    WHERE e.id = ANY (p_event_ids)
  ) AS checked
  WHERE checked.is_unavailable OR checked.is_outside_semester
$$;

-- -----------------------------------------------------------------------------
-- 3. Admin override
-- -----------------------------------------------------------------------------
--
-- An override is a pair of transaction-local settings that only the two RPC
-- functions below set, and only after confirming in this transaction that the
-- user is an enabled admin. The events trigger confirms it a second time
-- before honouring them. Browsers never reach PostgREST (the server key stays
-- on the backend), and PostgREST gives callers no way to set arbitrary
-- settings, so a request body cannot forge an override.

CREATE FUNCTION public.calendar_begin_override(p_actor_user_id integer, p_reason text)
RETURNS void
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
BEGIN
  IF p_reason IS NULL OR btrim(p_reason) = '' OR char_length(btrim(p_reason)) > 500 THEN
    RAISE EXCEPTION 'An override reason of 1 to 500 characters is required.'
      USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.users u
    WHERE u.id = p_actor_user_id
      AND u.enabled
      AND lower(coalesce(u.role_name, '')) = 'admin'
  ) THEN
    RAISE EXCEPTION 'Only an enabled administrator can override calendar restrictions.'
      USING ERRCODE = 'BTA01';
  END IF;

  PERFORM set_config('calendar.override_actor_id', p_actor_user_id::text, true);
  PERFORM set_config('calendar.override_reason', btrim(p_reason), true);
END
$$;

CREATE FUNCTION public.calendar_end_override()
RETURNS void
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
BEGIN
  PERFORM set_config('calendar.override_actor_id', '', true);
  PERFORM set_config('calendar.override_reason', '', true);
END
$$;

-- The admin behind the current transaction's override, confirmed again
-- against users. All fields are NULL when no override is active. A setting
-- naming anyone but an enabled admin, or missing its reason, raises BTA01.
CREATE FUNCTION public.calendar_override_admin(
  OUT actor_id integer,
  OUT actor_email text,
  OUT reason text
)
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
BEGIN
  actor_id := nullif(current_setting('calendar.override_actor_id', true), '')::integer;

  IF actor_id IS NULL THEN
    RETURN;
  END IF;

  reason := nullif(btrim(current_setting('calendar.override_reason', true)), '');

  SELECT u.email INTO actor_email
  FROM public.users u
  WHERE u.id = actor_id
    AND u.enabled
    AND lower(coalesce(u.role_name, '')) = 'admin';

  IF actor_email IS NULL OR reason IS NULL THEN
    RAISE EXCEPTION 'Only an enabled administrator can override calendar restrictions.'
      USING ERRCODE = 'BTA01';
  END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- 4. Reservation rules (events)
-- -----------------------------------------------------------------------------

-- Statement-level, so a batch or recurring insert is judged as a whole: every
-- offending date is reported together and, because the error aborts the
-- statement, not one row of the batch is kept.
CREATE FUNCTION public.events_enforce_calendar_rules()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
  v_event_ids integer[];
  v_unavailable date[];
  v_outside date[];
  v_actor_id integer;
  v_actor_email text;
  v_reason text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT array_agg(n.id) INTO v_event_ids FROM new_rows n;
  ELSE
    -- An edit is only re-checked when it moves the reservation to different
    -- local dates, so fixing the title of an existing booking stays possible.
    SELECT array_agg(n.id) INTO v_event_ids
    FROM new_rows n
    LEFT JOIN old_rows o ON o.id = n.id
    WHERE o.id IS NULL
       OR ARRAY(SELECT public.calendar_event_dates(o.start_time, o.end_time))
          IS DISTINCT FROM ARRAY(SELECT public.calendar_event_dates(n.start_time, n.end_time));
  END IF;

  IF v_event_ids IS NULL THEN
    RETURN NULL;
  END IF;

  -- Already held via events_calendar_lock whenever dates can change; taking
  -- it again here is free and keeps this function safe on its own.
  PERFORM public.calendar_lock();

  SELECT
    coalesce(array_agg(DISTINCT v.local_date ORDER BY v.local_date) FILTER (WHERE v.is_unavailable), '{}'),
    coalesce(array_agg(DISTINCT v.local_date ORDER BY v.local_date) FILTER (WHERE v.is_outside_semester), '{}')
  INTO v_unavailable, v_outside
  FROM public.calendar_event_violations(v_event_ids) AS v;

  IF cardinality(v_unavailable) = 0 AND cardinality(v_outside) = 0 THEN
    RETURN NULL;
  END IF;

  SELECT o.actor_id, o.actor_email, o.reason
  INTO v_actor_id, v_actor_email, v_reason
  FROM public.calendar_override_admin() AS o;

  IF v_actor_id IS NULL THEN
    RAISE EXCEPTION USING
      ERRCODE = 'BTC01',
      MESSAGE = CASE
        WHEN cardinality(v_unavailable) > 0 THEN 'Reservation date is unavailable.'
        ELSE 'Reservation date is outside the configured semester.'
      END,
      DETAIL = jsonb_build_object(
        'dates', (
          SELECT to_jsonb(array_agg(DISTINCT d ORDER BY d))
          FROM unnest(v_unavailable || v_outside) AS d
        ),
        'unavailable_dates', to_jsonb(v_unavailable),
        'outside_semester_dates', to_jsonb(v_outside)
      )::text;
  END IF;

  -- Override in effect (calendar_override_admin() confirmed the account):
  -- record what was bypassed.
  INSERT INTO public.calendar_override_audit (
    event_id, action, actor_user_id, actor_email, reason,
    overridden_rules, restricted_dates, event_start_time, event_end_time
  )
  SELECT
    e.id,
    CASE TG_OP WHEN 'INSERT' THEN 'create' ELSE 'update' END,
    v_actor_id,
    v_actor_email,
    v_reason,
    array_remove(ARRAY[
      CASE WHEN bool_or(v.is_outside_semester) THEN 'outside_semester' END,
      CASE WHEN bool_or(v.is_unavailable) THEN 'unavailable_day' END
    ], NULL),
    array_agg(DISTINCT v.local_date ORDER BY v.local_date),
    e.start_time,
    e.end_time
  FROM public.calendar_event_violations(v_event_ids) AS v
  JOIN public.events e ON e.id = v.event_id
  GROUP BY e.id, e.start_time, e.end_time;

  RETURN NULL;
END
$$;

CREATE TRIGGER events_calendar_lock
  BEFORE INSERT OR UPDATE OF start_time, end_time ON public.events
  FOR EACH STATEMENT EXECUTE FUNCTION public.calendar_lock_statement();

CREATE TRIGGER events_calendar_rules_on_insert
  AFTER INSERT ON public.events
  REFERENCING NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION public.events_enforce_calendar_rules();

CREATE TRIGGER events_calendar_rules_on_update
  AFTER UPDATE ON public.events
  REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION public.events_enforce_calendar_rules();

-- -----------------------------------------------------------------------------
-- 5. Unavailable-day rules
-- -----------------------------------------------------------------------------

CREATE FUNCTION public.unavailable_days_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
  v_semester_id bigint;
  v_day_start timestamp with time zone;
  v_day_end timestamp with time zone;
  v_conflicts jsonb;
  v_actor_id integer;
  v_actor_email text;
  v_reason text;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    NEW.created_at := OLD.created_at;
    NEW.created_by := OLD.created_by;
    NEW.updated_at := now();

    IF NEW.unavailable_date = OLD.unavailable_date
       AND NEW.semester_id IS NOT DISTINCT FROM OLD.semester_id THEN
      RETURN NEW;
    END IF;
  END IF;

  IF NEW.unavailable_date < public.calendar_local_date(now()) THEN
    RAISE EXCEPTION 'Unavailable days cannot be set in the past.'
      USING ERRCODE = 'BTC05',
            DETAIL = jsonb_build_object('date', NEW.unavailable_date)::text;
  END IF;

  SELECT s.id INTO v_semester_id
  FROM public.semesters s
  WHERE NEW.unavailable_date BETWEEN s.start_date AND s.end_date;

  IF v_semester_id IS NULL THEN
    RAISE EXCEPTION 'Unavailable days must fall within a configured semester.'
      USING ERRCODE = 'BTC04',
            DETAIL = jsonb_build_object('date', NEW.unavailable_date)::text;
  END IF;

  IF (TG_OP = 'INSERT' AND NEW.semester_id IS NULL)
     OR (TG_OP = 'UPDATE' AND NEW.semester_id IS NOT DISTINCT FROM OLD.semester_id) THEN
    NEW.semester_id := v_semester_id;
  ELSIF NEW.semester_id <> v_semester_id THEN
    RAISE EXCEPTION 'Unavailable days must fall within a configured semester.'
      USING ERRCODE = 'BTC04',
            DETAIL = jsonb_build_object(
              'date', NEW.unavailable_date,
              'semester_id', NEW.semester_id
            )::text;
  END IF;

  -- A day that already has bookings is only closed when an admin explicitly
  -- overrides (an emergency or maintenance closure). The bookings are kept,
  -- and each one gets a close_day row in calendar_override_audit.
  v_day_start := NEW.unavailable_date::timestamp AT TIME ZONE 'America/Los_Angeles';
  v_day_end := (NEW.unavailable_date + 1)::timestamp AT TIME ZONE 'America/Los_Angeles';

  SELECT jsonb_agg(
           jsonb_build_object(
             'id', e.id,
             'title', e.title,
             'start_time', e.start_time,
             'end_time', e.end_time,
             'host_user_id', e.host_user_id
           )
           ORDER BY e.start_time, e.id
         )
  INTO v_conflicts
  FROM public.events e
  WHERE e.start_time < v_day_end
    AND e.end_time > v_day_start;

  IF v_conflicts IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT o.actor_id, o.actor_email, o.reason
  INTO v_actor_id, v_actor_email, v_reason
  FROM public.calendar_override_admin() AS o;

  IF v_actor_id IS NULL THEN
    RAISE EXCEPTION 'Reservations already exist on this date.'
      USING ERRCODE = 'BTC02',
            DETAIL = jsonb_build_object(
              'date', NEW.unavailable_date,
              'reservations', v_conflicts
            )::text;
  END IF;

  INSERT INTO public.calendar_override_audit (
    event_id, action, actor_user_id, actor_email, reason,
    overridden_rules, restricted_dates, event_start_time, event_end_time
  )
  SELECT
    e.id, 'close_day', v_actor_id, v_actor_email, v_reason,
    ARRAY['existing_reservations'], ARRAY[NEW.unavailable_date], e.start_time, e.end_time
  FROM public.events e
  WHERE e.start_time < v_day_end
    AND e.end_time > v_day_start;

  RETURN NEW;
END
$$;

CREATE TRIGGER unavailable_days_calendar_lock
  BEFORE INSERT OR UPDATE ON public.unavailable_days
  FOR EACH STATEMENT EXECUTE FUNCTION public.calendar_lock_statement();

CREATE TRIGGER unavailable_days_guard
  BEFORE INSERT OR UPDATE ON public.unavailable_days
  FOR EACH ROW EXECUTE FUNCTION public.unavailable_days_guard();

-- -----------------------------------------------------------------------------
-- 6. Semester rules
-- -----------------------------------------------------------------------------

-- Shrinking or deleting a semester must not silently strand upcoming
-- reservations or the semester's own unavailable days. Past reservations are
-- not counted, so a finished semester can still be cleaned up.
CREATE FUNCTION public.semesters_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
  v_new_start date;
  v_new_end date;
  v_days jsonb;
  v_events jsonb;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    NEW.created_at := OLD.created_at;
    NEW.updated_at := now();

    IF NEW.start_date = OLD.start_date AND NEW.end_date = OLD.end_date THEN
      RETURN NEW;
    END IF;

    v_new_start := NEW.start_date;
    v_new_end := NEW.end_date;
  END IF;

  SELECT jsonb_agg(
           jsonb_build_object('id', u.id, 'unavailable_date', u.unavailable_date)
           ORDER BY u.unavailable_date
         )
  INTO v_days
  FROM public.unavailable_days u
  WHERE u.semester_id = OLD.id
    AND (v_new_start IS NULL OR u.unavailable_date NOT BETWEEN v_new_start AND v_new_end);

  SELECT jsonb_agg(
           jsonb_build_object(
             'id', e.id,
             'title', e.title,
             'start_time', e.start_time,
             'end_time', e.end_time,
             'host_user_id', e.host_user_id
           )
           ORDER BY e.start_time, e.id
         )
  INTO v_events
  FROM public.events e
  WHERE e.end_time > now()
    AND e.start_time < ((OLD.end_date + 1)::timestamp AT TIME ZONE 'America/Los_Angeles')
    AND e.end_time > (OLD.start_date::timestamp AT TIME ZONE 'America/Los_Angeles')
    AND EXISTS (
      SELECT 1
      FROM public.calendar_event_dates(e.start_time, e.end_time) AS d(local_date)
      WHERE d.local_date BETWEEN OLD.start_date AND OLD.end_date
        AND (v_new_start IS NULL OR d.local_date NOT BETWEEN v_new_start AND v_new_end)
        AND NOT EXISTS (
          SELECT 1
          FROM public.semesters s
          WHERE s.id <> OLD.id
            AND d.local_date BETWEEN s.start_date AND s.end_date
        )
    );

  IF v_days IS NOT NULL OR v_events IS NOT NULL THEN
    RAISE EXCEPTION 'This change would leave reservations or unavailable days outside every semester.'
      USING ERRCODE = 'BTC03',
            DETAIL = jsonb_build_object(
              'semester_id', OLD.id,
              'reservations', coalesce(v_events, '[]'::jsonb),
              'unavailable_days', coalesce(v_days, '[]'::jsonb)
            )::text;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER semesters_calendar_lock
  BEFORE UPDATE OR DELETE ON public.semesters
  FOR EACH STATEMENT EXECUTE FUNCTION public.calendar_lock_statement();

CREATE TRIGGER semesters_guard
  BEFORE UPDATE OR DELETE ON public.semesters
  FOR EACH ROW EXECUTE FUNCTION public.semesters_guard();

-- -----------------------------------------------------------------------------
-- 7. Override RPC functions (POST /rest/v1/rpc/<name>, backend only)
-- -----------------------------------------------------------------------------

-- Inserts all reservations in one statement: either every row is written
-- (with an audit row for each one whose rules were bypassed) or none is.
CREATE FUNCTION public.create_events_with_calendar_override(
  p_actor_user_id integer,
  p_reason text,
  p_events jsonb
)
RETURNS SETOF public.events
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
BEGIN
  -- Lock before touching any row so lock order matches every other writer.
  PERFORM public.calendar_lock();
  PERFORM public.calendar_begin_override(p_actor_user_id, p_reason);

  IF jsonb_typeof(p_events) IS DISTINCT FROM 'array' OR jsonb_array_length(p_events) = 0 THEN
    RAISE EXCEPTION 'At least one reservation is required.' USING ERRCODE = '22023';
  END IF;

  RETURN QUERY
    WITH inserted AS (
      INSERT INTO public.events (
        host_user_id, start_time, end_time, event_type, description,
        title, department, is_public, recurrence_group_id
      )
      SELECT
        r.host_user_id, r.start_time, r.end_time, r.event_type, r.description,
        r.title, r.department, r.is_public, r.recurrence_group_id
      FROM jsonb_populate_recordset(NULL::public.events, p_events) AS r
      RETURNING *
    )
    SELECT * FROM inserted;

  PERFORM public.calendar_end_override();
END
$$;

-- Applies the given fields to one reservation. Returns no rows when the id
-- does not exist.
CREATE FUNCTION public.update_event_with_calendar_override(
  p_actor_user_id integer,
  p_reason text,
  p_event_id integer,
  p_event jsonb
)
RETURNS SETOF public.events
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
DECLARE
  v_current public.events;
  v_next public.events;
BEGIN
  PERFORM public.calendar_lock();
  PERFORM public.calendar_begin_override(p_actor_user_id, p_reason);

  IF jsonb_typeof(p_event) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'Reservation changes must be a JSON object.' USING ERRCODE = '22023';
  END IF;

  SELECT * INTO v_current FROM public.events WHERE id = p_event_id FOR UPDATE;

  IF NOT FOUND THEN
    PERFORM public.calendar_end_override();
    RETURN;
  END IF;

  v_next := jsonb_populate_record(v_current, p_event - 'id');

  RETURN QUERY
    WITH updated AS (
      UPDATE public.events
      SET host_user_id = v_next.host_user_id,
          start_time = v_next.start_time,
          end_time = v_next.end_time,
          event_type = v_next.event_type,
          description = v_next.description,
          title = v_next.title,
          department = v_next.department,
          is_public = v_next.is_public,
          recurrence_group_id = v_next.recurrence_group_id
      WHERE id = p_event_id
      RETURNING *
    )
    SELECT * FROM updated;

  PERFORM public.calendar_end_override();
END
$$;

-- Closes a day even if it already has reservations. Every other closure rule
-- (inside a semester, not in the past, one per date) still applies.
CREATE FUNCTION public.create_unavailable_day_with_override(
  p_actor_user_id integer,
  p_reason text,
  p_day jsonb
)
RETURNS SETOF public.unavailable_days
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
BEGIN
  PERFORM public.calendar_lock();
  PERFORM public.calendar_begin_override(p_actor_user_id, p_reason);

  IF jsonb_typeof(p_day) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'The unavailable day must be a JSON object.' USING ERRCODE = '22023';
  END IF;

  RETURN QUERY
    WITH inserted AS (
      INSERT INTO public.unavailable_days (semester_id, unavailable_date, reason, created_by)
      SELECT r.semester_id, r.unavailable_date, r.reason, r.created_by
      FROM jsonb_populate_record(NULL::public.unavailable_days, p_day) AS r
      RETURNING *
    )
    SELECT * FROM inserted;

  PERFORM public.calendar_end_override();
END
$$;

-- Moves a closure (or edits it) even onto a day that has reservations.
-- Returns no rows when the id does not exist.
CREATE FUNCTION public.update_unavailable_day_with_override(
  p_actor_user_id integer,
  p_reason text,
  p_unavailable_day_id bigint,
  p_changes jsonb
)
RETURNS SETOF public.unavailable_days
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog
AS $$
DECLARE
  v_current public.unavailable_days;
  v_next public.unavailable_days;
BEGIN
  PERFORM public.calendar_lock();
  PERFORM public.calendar_begin_override(p_actor_user_id, p_reason);

  IF jsonb_typeof(p_changes) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'Unavailable day changes must be a JSON object.' USING ERRCODE = '22023';
  END IF;

  SELECT * INTO v_current FROM public.unavailable_days WHERE id = p_unavailable_day_id FOR UPDATE;

  IF NOT FOUND THEN
    PERFORM public.calendar_end_override();
    RETURN;
  END IF;

  v_next := jsonb_populate_record(v_current, p_changes - 'id');

  RETURN QUERY
    WITH updated AS (
      UPDATE public.unavailable_days
      SET unavailable_date = v_next.unavailable_date,
          semester_id = v_next.semester_id,
          reason = v_next.reason
      WHERE id = p_unavailable_day_id
      RETURNING *
    )
    SELECT * FROM updated;

  PERFORM public.calendar_end_override();
END
$$;

-- -----------------------------------------------------------------------------
-- 8. Access control (matches the existing deny-direct-access setup)
-- -----------------------------------------------------------------------------

ALTER TABLE public.semesters ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.unavailable_days ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.calendar_override_audit ENABLE ROW LEVEL SECURITY;

CREATE POLICY deny_direct_api_access ON public.semesters
  AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
CREATE POLICY deny_direct_api_access ON public.unavailable_days
  AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
CREATE POLICY deny_direct_api_access ON public.calendar_override_audit
  AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);

-- Supabase's default privileges grant new tables and functions to anon and
-- authenticated; take those away explicitly.
REVOKE ALL ON TABLE public.semesters, public.unavailable_days, public.calendar_override_audit
  FROM PUBLIC, anon, authenticated, service_role;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.semesters, public.unavailable_days
  TO service_role;
-- Append-only for the backend.
GRANT SELECT, INSERT ON TABLE public.calendar_override_audit TO service_role;

-- The identity sequences inherit the same default grants. Inserts through the
-- tables do not need them, so clients get nothing (no nextval/setval).
REVOKE ALL ON SEQUENCE
  public.semesters_id_seq,
  public.unavailable_days_id_seq,
  public.calendar_override_audit_id_seq
  FROM PUBLIC, anon, authenticated;

REVOKE ALL ON FUNCTION
  public.calendar_local_date(timestamp with time zone),
  public.calendar_event_dates(timestamp with time zone, timestamp with time zone),
  public.calendar_lock(),
  public.calendar_lock_statement(),
  public.calendar_event_violations(integer[]),
  public.calendar_begin_override(integer, text),
  public.calendar_end_override(),
  public.calendar_override_admin(),
  public.events_enforce_calendar_rules(),
  public.unavailable_days_guard(),
  public.semesters_guard(),
  public.create_events_with_calendar_override(integer, text, jsonb),
  public.update_event_with_calendar_override(integer, text, integer, jsonb),
  public.create_unavailable_day_with_override(integer, text, jsonb),
  public.update_unavailable_day_with_override(integer, text, bigint, jsonb)
  FROM PUBLIC, anon, authenticated;

-- The trigger bodies run as the writing role, so it needs the helpers too.
GRANT EXECUTE ON FUNCTION
  public.calendar_local_date(timestamp with time zone),
  public.calendar_event_dates(timestamp with time zone, timestamp with time zone),
  public.calendar_lock(),
  public.calendar_event_violations(integer[]),
  public.calendar_begin_override(integer, text),
  public.calendar_end_override(),
  public.calendar_override_admin(),
  public.create_events_with_calendar_override(integer, text, jsonb),
  public.update_event_with_calendar_override(integer, text, integer, jsonb),
  public.create_unavailable_day_with_override(integer, text, jsonb),
  public.update_unavailable_day_with_override(integer, text, bigint, jsonb)
  TO service_role;

-- Tell PostgREST about the new tables and functions.
NOTIFY pgrst, 'reload schema';

COMMIT;
