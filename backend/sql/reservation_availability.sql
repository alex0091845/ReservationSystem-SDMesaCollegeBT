BEGIN;

LOCK TABLE public.events IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE IF NOT EXISTS public.reservation_availability (
  id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  last_bookable_date date,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
INSERT INTO public.reservation_availability (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
ALTER TABLE public.reservation_availability ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.reservation_availability FROM PUBLIC, anon, authenticated;
GRANT SELECT, UPDATE ON public.reservation_availability TO service_role;

DROP TRIGGER IF EXISTS events_reservation_availability ON public.events;
ALTER TABLE public.events ADD COLUMN IF NOT EXISTS is_closure boolean NOT NULL DEFAULT false;
ALTER TABLE public.events ALTER COLUMN host_user_id DROP NOT NULL;

DO $$
DECLARE
  invalid_id integer;
  overlap_ids integer[];
BEGIN
  SELECT id INTO invalid_id FROM public.events WHERE end_time <= start_time ORDER BY id LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION USING ERRCODE = '23514',
      MESSAGE = format('Reservation %s has an invalid time range.', invalid_id),
      HINT = 'Correct the existing time range before rerunning this migration. No records have been deleted.';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.events'::regclass AND conname = 'events_positive_duration') THEN
    ALTER TABLE public.events ADD CONSTRAINT events_positive_duration CHECK (end_time > start_time);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.events'::regclass AND conname = 'events_no_reservation_overlap') THEN
    SELECT ARRAY[a.id, b.id] INTO overlap_ids
    FROM public.events a JOIN public.events b ON a.id < b.id
      AND a.start_time < b.end_time AND a.end_time > b.start_time
    WHERE NOT a.is_closure AND NOT b.is_closure ORDER BY a.id, b.id LIMIT 1;
    IF FOUND THEN
      RAISE EXCEPTION USING ERRCODE = '23P01',
        MESSAGE = format('Reservations %s and %s overlap.', overlap_ids[1], overlap_ids[2]),
        HINT = 'Resolve the existing overlap before rerunning this migration. No records have been deleted.';
    END IF;
    -- Half-open ranges allow adjacent bookings and reject simultaneous conflicting writes
    ALTER TABLE public.events ADD CONSTRAINT events_no_reservation_overlap
      EXCLUDE USING gist (tstzrange(start_time, end_time, '[)') WITH &&) WHERE (NOT is_closure);
  END IF;
END;
$$;

INSERT INTO public.event_types (event_type, description)
VALUES ('Unavailable', 'Room closure') ON CONFLICT (event_type) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.events'::regclass AND conname = 'events_reservation_host_required') THEN
    ALTER TABLE public.events ADD CONSTRAINT events_reservation_host_required
      CHECK (is_closure OR host_user_id IS NOT NULL);
  END IF;
END;
$$;
ALTER TABLE public.events DROP CONSTRAINT IF EXISTS events_closure_shape;
-- Keep the same closure dates and details while normalizing existing event hours
UPDATE public.events
SET start_time = ((start_time AT TIME ZONE 'America/Los_Angeles')::date + time '08:00') AT TIME ZONE 'America/Los_Angeles',
    end_time = ((start_time AT TIME ZONE 'America/Los_Angeles')::date + time '17:00') AT TIME ZONE 'America/Los_Angeles'
WHERE is_closure AND (
  start_time IS DISTINCT FROM (((start_time AT TIME ZONE 'America/Los_Angeles')::date + time '08:00') AT TIME ZONE 'America/Los_Angeles')
  OR end_time IS DISTINCT FROM (((start_time AT TIME ZONE 'America/Los_Angeles')::date + time '17:00') AT TIME ZONE 'America/Los_Angeles')
);
ALTER TABLE public.events ADD CONSTRAINT events_closure_shape CHECK (
  NOT is_closure OR (is_public IS TRUE AND event_type IS NOT DISTINCT FROM 'Unavailable'
    AND start_time = (((start_time AT TIME ZONE 'America/Los_Angeles')::date + time '08:00') AT TIME ZONE 'America/Los_Angeles')
    AND end_time = (((start_time AT TIME ZONE 'America/Los_Angeles')::date + time '17:00') AT TIME ZONE 'America/Los_Angeles'))
);
CREATE UNIQUE INDEX IF NOT EXISTS events_closure_date_unique
  ON public.events (((start_time AT TIME ZONE 'America/Los_Angeles')::date)) WHERE is_closure;

-- Convert the previous date array before removing that source of truth
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
             AND table_name = 'reservation_availability' AND column_name = 'invalid_dates') THEN
    EXECUTE $migration$
      INSERT INTO public.events (host_user_id, start_time, end_time, event_type, title, description, is_public, is_closure)
      SELECT NULL, (day + time '08:00') AT TIME ZONE 'America/Los_Angeles',
        (day + time '17:00') AT TIME ZONE 'America/Los_Angeles',
        'Unavailable', 'Unavailable', 'Room unavailable', true, true
      FROM public.reservation_availability s CROSS JOIN LATERAL unnest(s.invalid_dates) AS blocked(day)
      ON CONFLICT (((start_time AT TIME ZONE 'America/Los_Angeles')::date)) WHERE is_closure DO NOTHING
    $migration$;
  END IF;
END;
$$;
ALTER TABLE public.reservation_availability DROP COLUMN IF EXISTS invalid_dates;

CREATE OR REPLACE FUNCTION public.get_reservation_availability()
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT jsonb_build_object(
    'last_bookable_date', s.last_bookable_date,
    'invalid_dates', COALESCE((SELECT jsonb_agg((e.start_time AT TIME ZONE 'America/Los_Angeles')::date ORDER BY e.start_time)
      FROM public.events e WHERE e.is_closure
        AND (e.start_time AT TIME ZONE 'America/Los_Angeles')::date <= s.last_bookable_date), '[]'::jsonb),
    'version', s.version)
  FROM public.reservation_availability s WHERE s.id = 1;
$$;

CREATE OR REPLACE FUNCTION public.enforce_reservation_availability()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  settings public.reservation_availability%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.is_closure THEN
      UPDATE public.reservation_availability SET version = version + 1 WHERE id = 1;
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF OLD.is_closure OR NEW.is_closure THEN
      UPDATE public.reservation_availability SET version = version + 1 WHERE id = 1;
    END IF;
    IF NEW.is_closure THEN RETURN NEW; END IF;
    IF NOT OLD.is_closure AND NEW.start_time IS NOT DISTINCT FROM OLD.start_time
       AND NEW.end_time IS NOT DISTINCT FROM OLD.end_time THEN RETURN NEW; END IF;
  ELSIF NEW.is_closure THEN
    UPDATE public.reservation_availability SET version = version + 1 WHERE id = 1;
    RETURN NEW;
  END IF;

  -- The same settings lock serializes bookings and changes to closure events
  SELECT * INTO settings FROM public.reservation_availability WHERE id = 1 FOR SHARE;
  IF NOT FOUND OR settings.last_bookable_date IS NULL THEN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Reservation availability has not been configured.';
  END IF;
  IF (NEW.start_time AT TIME ZONE 'America/Los_Angeles')::date > settings.last_bookable_date
     OR (NEW.end_time AT TIME ZONE 'America/Los_Angeles')::date > settings.last_bookable_date THEN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Reservation is after the last bookable date.';
  END IF;
  IF EXISTS (SELECT 1 FROM public.events e WHERE e.is_closure
             AND (e.start_time AT TIME ZONE 'America/Los_Angeles')::date
               BETWEEN (NEW.start_time AT TIME ZONE 'America/Los_Angeles')::date
                 AND ((NEW.end_time - interval '1 microsecond') AT TIME ZONE 'America/Los_Angeles')::date) THEN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Reservation falls on an unavailable date.';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER events_reservation_availability BEFORE INSERT OR UPDATE OR DELETE ON public.events
FOR EACH ROW EXECUTE FUNCTION public.enforce_reservation_availability();

DROP FUNCTION IF EXISTS public.save_reservation_availability(date, date[], bigint);
CREATE OR REPLACE FUNCTION public.save_reservation_availability(
  p_last_bookable_date date, p_invalid_dates date[], p_version bigint,
  p_confirmed_cancellations integer[] DEFAULT '{}'::integer[])
RETURNS jsonb LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  settings public.reservation_availability%ROWTYPE;
  today date := (now() AT TIME ZONE 'America/Los_Angeles')::date;
  cancellations jsonb;
BEGIN
  IF (cardinality(p_invalid_dates) > 0 AND array_ndims(p_invalid_dates) <> 1)
     OR (cardinality(p_confirmed_cancellations) > 0 AND array_ndims(p_confirmed_cancellations) <> 1) THEN
    RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'Invalid availability settings.';
  END IF;
  IF p_last_bookable_date IS NULL OR p_last_bookable_date < today OR p_version IS NULL OR p_version < 0
     OR p_invalid_dates IS NULL OR array_position(p_invalid_dates, NULL) IS NOT NULL
     OR array_position(p_confirmed_cancellations, NULL) IS NOT NULL
     OR EXISTS (SELECT 1 FROM unnest(p_invalid_dates) AS blocked(day) WHERE day > p_last_bookable_date) THEN
    RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'Invalid availability settings.';
  END IF;
  SELECT * INTO settings FROM public.reservation_availability WHERE id = 1 FOR UPDATE;
  IF NOT FOUND OR settings.version <> p_version THEN
    RAISE EXCEPTION USING ERRCODE = '40001', MESSAGE = 'Another admin changed availability.';
  END IF;

  SELECT COALESCE(jsonb_agg(jsonb_build_object('id', e.id, 'title', e.title,
    'start_time', e.start_time, 'end_time', e.end_time) ORDER BY e.start_time, e.id), '[]'::jsonb)
  INTO cancellations FROM public.events e
  WHERE NOT e.is_closure AND e.end_time > now()
    AND ((e.start_time AT TIME ZONE 'America/Los_Angeles')::date > p_last_bookable_date
      OR (e.start_time AT TIME ZONE 'America/Los_Angeles')::date = ANY(p_invalid_dates));
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(cancellations) AS planned(event)
    WHERE NOT ((event->>'id')::integer = ANY(COALESCE(p_confirmed_cancellations, '{}'::integer[])))) THEN
    RETURN jsonb_build_object('requires_confirmation', true, 'reservations', cancellations);
  END IF;

  INSERT INTO public.event_types (event_type, description) VALUES ('Unavailable', 'Room closure')
    ON CONFLICT (event_type) DO NOTHING;
  DELETE FROM public.attendees
  WHERE event_id IN (SELECT (event->>'id')::integer FROM jsonb_array_elements(cancellations) AS planned(event));
  DELETE FROM public.events
  WHERE id IN (SELECT (event->>'id')::integer FROM jsonb_array_elements(cancellations) AS planned(event));

  -- Only reconcile editable dates; keep past records and closures beyond the cutoff
  DELETE FROM public.events WHERE is_closure
    AND (start_time AT TIME ZONE 'America/Los_Angeles')::date BETWEEN today AND p_last_bookable_date
    AND NOT ((start_time AT TIME ZONE 'America/Los_Angeles')::date = ANY(p_invalid_dates));
  INSERT INTO public.events (host_user_id, start_time, end_time, event_type, title, description, is_public, is_closure)
  SELECT NULL, (day + time '08:00') AT TIME ZONE 'America/Los_Angeles',
    (day + time '17:00') AT TIME ZONE 'America/Los_Angeles',
    'Unavailable', 'Unavailable', 'Room unavailable', true, true
  FROM (SELECT DISTINCT day FROM unnest(p_invalid_dates) AS blocked(day) WHERE day >= today) AS desired
  WHERE NOT EXISTS (SELECT 1 FROM public.events e WHERE e.is_closure
    AND (e.start_time AT TIME ZONE 'America/Los_Angeles')::date = desired.day);

  UPDATE public.reservation_availability SET last_bookable_date = p_last_bookable_date,
    version = settings.version + 1 WHERE id = 1;
  RETURN public.get_reservation_availability();
END;
$$;

CREATE OR REPLACE FUNCTION public.prevent_closure_check_in()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.events WHERE id = NEW.event_id AND is_closure) THEN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Unavailable dates cannot accept check-ins.';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS attendees_no_closure_check_in ON public.attendees;
CREATE TRIGGER attendees_no_closure_check_in BEFORE INSERT OR UPDATE OF event_id ON public.attendees
FOR EACH ROW EXECUTE FUNCTION public.prevent_closure_check_in();

REVOKE ALL ON FUNCTION public.get_reservation_availability(),
  public.save_reservation_availability(date, date[], bigint, integer[]), public.enforce_reservation_availability(),
  public.prevent_closure_check_in() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_reservation_availability(),
  public.save_reservation_availability(date, date[], bigint, integer[]), public.enforce_reservation_availability(),
  public.prevent_closure_check_in() TO service_role;

NOTIFY pgrst, 'reload schema';
COMMIT;
