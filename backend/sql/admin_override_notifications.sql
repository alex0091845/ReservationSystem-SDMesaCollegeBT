-- Admin special reservations, closure emails, and the admin review of cancelled reservations.
-- Run straight after backend/sql/reservation_availability.sql, whose availability trigger and save
-- function use the tables and helpers defined here. Both scripts can be rerun.
--
-- Error codes raised here and in reservation_availability.sql (the backend maps them to HTTP responses):
--   BTA01  the acting account is not an enabled admin                     403
--   BTN01  review not found                                               404
--   BTN02  follow-up attempted before the review was completed            409
--   BTS01  special reservation on a date that is not closed               409
--   BTS02  special reservation after the last bookable date               409
--   BTV01  availability changed since it was read                         409
BEGIN;

LOCK TABLE public.events IN SHARE ROW EXCLUSIVE MODE;

-- The audit trail, and the only thing that lets a reservation sit on a closed date.
-- No foreign keys: these records outlive the reservation and the accounts they mention.
CREATE TABLE IF NOT EXISTS public.reservation_overrides (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id integer NOT NULL UNIQUE,
  override_date date NOT NULL,
  reason text NOT NULL CHECK (btrim(reason) <> '' AND char_length(reason) <= 500),
  actor_user_id integer NOT NULL,
  actor_email text NOT NULL,
  host_user_id integer NOT NULL,
  event_title text,
  event_start_time timestamp with time zone NOT NULL,
  event_end_time timestamp with time zone NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now()
);

-- One row per reservation a confirmed closure cancelled. The reservation row is gone by the time
-- anyone reviews it, so this keeps what an admin needs. Check-ins are counted, not copied, because
-- they hold student details.
CREATE TABLE IF NOT EXISTS public.closure_reviews (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  batch_id uuid NOT NULL,
  event_id integer NOT NULL UNIQUE,
  cause text NOT NULL CHECK (cause IN ('unavailable_date', 'after_last_bookable_date')),
  closure_reason text,
  reservation_date date NOT NULL,
  event_title text,
  event_description text,
  event_type text,
  department text,
  is_public boolean,
  recurrence_group_id text,
  event_start_time timestamp with time zone NOT NULL,
  event_end_time timestamp with time zone NOT NULL,
  host_user_id integer,
  host_first_name text,
  host_last_name text,
  host_email text,
  check_in_count integer NOT NULL DEFAULT 0,
  canceled_by integer NOT NULL,
  canceled_at timestamp with time zone NOT NULL DEFAULT now(),
  review_status text NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'reviewed')),
  reviewed_by integer,
  reviewed_at timestamp with time zone,
  review_note text,
  CONSTRAINT closure_reviews_reviewer_check CHECK (
    (review_status = 'pending' AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR (review_status = 'reviewed' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS closure_reviews_batch_idx ON public.closure_reviews (batch_id);
CREATE INDEX IF NOT EXISTS closure_reviews_status_idx ON public.closure_reviews (review_status, canceled_at);

-- The outbox. Rows are written in the same transaction as the change that causes them, so a
-- refused or failed change can never produce an email; the backend sends them afterwards.
-- kind:   canceled        = the system-generated notice to the reservation's host
--         admin_review    = one summary per admin per confirmed save
--         admin_follow_up = a message an admin wrote after completing a review
-- status: pending -> sending -> sent, or back to pending for a retry
--         failed  = gave up; an admin can queue it again
--         skipped = no longer useful, so nothing was sent (last_error says why)
CREATE TABLE IF NOT EXISTS public.closure_notifications (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('canceled', 'admin_review', 'admin_follow_up')),
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'sending', 'sent', 'failed', 'skipped')),
  review_id bigint REFERENCES public.closure_reviews(id),
  batch_id uuid,
  -- The address is read at send time, so this table stores none.
  recipient_user_id integer REFERENCES public.users(id) ON DELETE SET NULL,
  follow_up_message text CHECK (char_length(follow_up_message) <= 2000),
  created_by integer,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamp with time zone NOT NULL DEFAULT now(),
  locked_until timestamp with time zone,
  -- A short code such as send_failed, never provider text.
  last_error text,
  sent_via text,
  sent_at timestamp with time zone,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT closure_notifications_shape CHECK (
    (kind = 'canceled' AND review_id IS NOT NULL AND batch_id IS NULL AND follow_up_message IS NULL)
    OR (kind = 'admin_review' AND review_id IS NULL AND batch_id IS NOT NULL AND follow_up_message IS NULL)
    OR (kind = 'admin_follow_up' AND review_id IS NOT NULL AND batch_id IS NULL AND follow_up_message IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS closure_notifications_due_idx ON public.closure_notifications (next_attempt_at, id)
  WHERE status IN ('pending', 'sending');
CREATE INDEX IF NOT EXISTS closure_notifications_review_idx ON public.closure_notifications (review_id);
-- However often anything is repeated, each of these goes out once.
CREATE UNIQUE INDEX IF NOT EXISTS closure_notifications_canceled_once
  ON public.closure_notifications (review_id) WHERE kind = 'canceled';
CREATE UNIQUE INDEX IF NOT EXISTS closure_notifications_admin_once
  ON public.closure_notifications (batch_id, recipient_user_id) WHERE kind = 'admin_review';
CREATE UNIQUE INDEX IF NOT EXISTS closure_notifications_follow_up_once
  ON public.closure_notifications (review_id, md5(follow_up_message)) WHERE kind = 'admin_follow_up';

CREATE OR REPLACE FUNCTION public.require_enabled_admin(p_user_id integer)
RETURNS void LANGUAGE plpgsql STABLE SET search_path = '' AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.users u
                 WHERE u.id = p_user_id AND u.enabled AND lower(coalesce(u.role_name, '')) = 'admin') THEN
    RAISE EXCEPTION USING ERRCODE = 'BTA01', MESSAGE = 'Only an enabled admin can do this.';
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.create_special_reservation(
  p_actor_user_id integer, p_reason text, p_event jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  settings public.reservation_availability%ROWTYPE;
  reason text := btrim(p_reason);
  starts timestamp with time zone := (p_event->>'start_time')::timestamp with time zone;
  ends timestamp with time zone := (p_event->>'end_time')::timestamp with time zone;
  local_start timestamp := starts AT TIME ZONE 'America/Los_Angeles';
  local_end timestamp := ends AT TIME ZONE 'America/Los_Angeles';
  reservation_id integer;
  override_id bigint;
  created public.events%ROWTYPE;
BEGIN
  PERFORM public.require_enabled_admin(p_actor_user_id);
  IF reason IS NULL OR reason = '' OR char_length(reason) > 500 THEN
    RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'An override reason of up to 500 characters is required.';
  END IF;
  IF starts IS NULL OR ends IS NULL OR ends <= starts OR starts <= now()
     OR local_start::date <> local_end::date OR local_end::time > time '17:00'
     OR lower(coalesce(p_event->>'event_type', '')) = 'unavailable' THEN
    RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'Invalid special reservation.';
  END IF;

  SELECT * INTO settings FROM public.reservation_availability WHERE id = 1 FOR SHARE;
  IF NOT FOUND OR settings.last_bookable_date IS NULL OR local_start::date > settings.last_bookable_date THEN
    RAISE EXCEPTION USING ERRCODE = 'BTS02', MESSAGE = 'The date is after the last bookable date.';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.events e WHERE e.is_closure
                 AND (e.start_time AT TIME ZONE 'America/Los_Angeles')::date = local_start::date) THEN
    RAISE EXCEPTION USING ERRCODE = 'BTS01', MESSAGE = 'The date is not unavailable.';
  END IF;

  -- The audit row comes first because the events trigger looks for it
  reservation_id := nextval('public.reservations_reservation_id_seq');
  INSERT INTO public.reservation_overrides (event_id, override_date, reason, actor_user_id, actor_email,
    host_user_id, event_title, event_start_time, event_end_time)
  SELECT reservation_id, local_start::date, reason, u.id, u.email, (p_event->>'host_user_id')::integer,
    p_event->>'title', starts, ends
  FROM public.users u WHERE u.id = p_actor_user_id
  RETURNING id INTO override_id;

  INSERT INTO public.events (id, host_user_id, start_time, end_time, event_type, description, title,
    department, is_public, is_closure)
  VALUES (reservation_id, (p_event->>'host_user_id')::integer, starts, ends, p_event->>'event_type',
    p_event->>'description', p_event->>'title', p_event->>'department',
    (p_event->>'is_public')::boolean, false)
  RETURNING * INTO created;

  RETURN jsonb_build_object('reservation', to_jsonb(created), 'override_id', override_id);
END;
$$;

-- Called by save_reservation_availability before it deletes the reservations, so the review rows
-- and every email commit or roll back with the cancellation. Nothing else can call it.
CREATE OR REPLACE FUNCTION public.queue_closure_cancellations(
  p_event_ids integer[], p_invalid_dates date[], p_actor_user_id integer, p_closure_reason text)
RETURNS void LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  batch uuid := gen_random_uuid();
BEGIN
  WITH saved AS (
    INSERT INTO public.closure_reviews (batch_id, event_id, cause, closure_reason, reservation_date,
      event_title, event_description, event_type, department, is_public, recurrence_group_id,
      event_start_time, event_end_time, host_user_id, host_first_name, host_last_name, host_email,
      check_in_count, canceled_by)
    SELECT batch, e.id,
      CASE WHEN (e.start_time AT TIME ZONE 'America/Los_Angeles')::date = ANY(p_invalid_dates)
        THEN 'unavailable_date' ELSE 'after_last_bookable_date' END,
      p_closure_reason, (e.start_time AT TIME ZONE 'America/Los_Angeles')::date,
      e.title, e.description, e.event_type, e.department, e.is_public, e.recurrence_group_id,
      e.start_time, e.end_time, e.host_user_id, u.first_name, u.last_name, u.email,
      (SELECT count(*) FROM public.attendees a WHERE a.event_id = e.id), p_actor_user_id
    FROM public.events e LEFT JOIN public.users u ON u.id = e.host_user_id
    WHERE e.id = ANY(p_event_ids)
    RETURNING id, host_user_id
  )
  INSERT INTO public.closure_notifications (kind, review_id, recipient_user_id, created_by)
  SELECT 'canceled', s.id, s.host_user_id, p_actor_user_id FROM saved s;

  INSERT INTO public.closure_notifications (kind, batch_id, recipient_user_id, created_by)
  SELECT 'admin_review', batch, u.id, p_actor_user_id
  FROM public.users u WHERE u.enabled AND lower(coalesce(u.role_name, '')) = 'admin';
END;
$$;

-- Records that a person looked at a cancellation. Repeating it changes nothing, so the first
-- reviewer and note stay on the record.
CREATE OR REPLACE FUNCTION public.mark_closure_review(p_actor_user_id integer, p_review_id bigint, p_note text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  review public.closure_reviews%ROWTYPE;
  note text := nullif(btrim(p_note), '');
BEGIN
  PERFORM public.require_enabled_admin(p_actor_user_id);
  IF char_length(note) > 2000 THEN
    RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'The review note can be up to 2000 characters.';
  END IF;
  UPDATE public.closure_reviews SET review_status = 'reviewed', reviewed_by = p_actor_user_id,
    reviewed_at = now(), review_note = note
  WHERE id = p_review_id AND review_status = 'pending';
  SELECT * INTO review FROM public.closure_reviews WHERE id = p_review_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION USING ERRCODE = 'BTN01', MESSAGE = 'Review not found.';
  END IF;
  RETURN to_jsonb(review);
END;
$$;

-- A follow-up says an admin looked at the reservation, so it needs a completed review first.
-- Repeating the same message returns the earlier one instead of sending it twice.
CREATE OR REPLACE FUNCTION public.queue_closure_follow_up(p_actor_user_id integer, p_review_id bigint, p_message text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  review public.closure_reviews%ROWTYPE;
  message text := btrim(p_message);
  queued public.closure_notifications%ROWTYPE;
BEGIN
  PERFORM public.require_enabled_admin(p_actor_user_id);
  IF message IS NULL OR message = '' OR char_length(message) > 2000 THEN
    RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'A follow-up message of up to 2000 characters is required.';
  END IF;
  SELECT * INTO review FROM public.closure_reviews WHERE id = p_review_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION USING ERRCODE = 'BTN01', MESSAGE = 'Review not found.';
  END IF;
  IF review.review_status <> 'reviewed' THEN
    RAISE EXCEPTION USING ERRCODE = 'BTN02', MESSAGE = 'Mark the review complete before sending a follow-up.';
  END IF;
  INSERT INTO public.closure_notifications (kind, review_id, recipient_user_id, follow_up_message, created_by)
  VALUES ('admin_follow_up', review.id, (SELECT u.id FROM public.users u WHERE u.id = review.host_user_id),
    message, p_actor_user_id)
  ON CONFLICT (review_id, md5(follow_up_message)) WHERE kind = 'admin_follow_up' DO NOTHING
  RETURNING * INTO queued;
  IF NOT FOUND THEN
    SELECT * INTO queued FROM public.closure_notifications n
    WHERE n.kind = 'admin_follow_up' AND n.review_id = review.id AND md5(n.follow_up_message) = md5(message);
  END IF;
  RETURN jsonb_build_object('id', queued.id, 'kind', queued.kind, 'status', queued.status,
    'review_id', queued.review_id, 'created_at', queued.created_at);
END;
$$;

-- Hands out due notices with their recipient and marks them as sending. First it skips notices
-- that should no longer go out. SKIP LOCKED keeps two backends from claiming the same row. A row
-- left in sending by a crash is claimed again once its lease runs out, so delivery is at-least-once.
-- review_status is read here, at send time, so an email never says a finished review is pending.
DROP FUNCTION IF EXISTS public.claim_closure_notifications(integer, integer);
CREATE FUNCTION public.claim_closure_notifications(p_limit integer, p_lease_seconds integer)
RETURNS TABLE (
  id bigint,
  kind text,
  attempts integer,
  review_status text,
  cause text,
  closure_reason text,
  event_title text,
  event_start_time timestamp with time zone,
  event_end_time timestamp with time zone,
  follow_up_message text,
  batch_reservations jsonb,
  recipient_email text,
  recipient_first_name text,
  recipient_last_name text
)
LANGUAGE plpgsql VOLATILE SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
  WITH stale AS (
    SELECT n.id, CASE n.kind WHEN 'canceled' THEN 'reservation_passed' ELSE 'recipient_not_admin' END AS skip_reason
    FROM public.closure_notifications n
    LEFT JOIN public.closure_reviews r ON r.id = n.review_id
    LEFT JOIN public.users u ON u.id = n.recipient_user_id
    WHERE (n.status = 'pending' OR (n.status = 'sending' AND n.locked_until < now()))
      AND ((n.kind = 'canceled' AND r.event_end_time <= now())
        OR (n.kind = 'admin_review'
          AND NOT coalesce(u.enabled AND lower(coalesce(u.role_name, '')) = 'admin', false)))
    FOR UPDATE OF n SKIP LOCKED
  )
  UPDATE public.closure_notifications n
  SET status = 'skipped', last_error = c.skip_reason, locked_until = NULL
  FROM stale c
  WHERE n.id = c.id;

  RETURN QUERY
    WITH due AS (
      SELECT n.id FROM public.closure_notifications n
      WHERE (n.status = 'pending' AND n.next_attempt_at <= now())
         OR (n.status = 'sending' AND n.locked_until < now())
      ORDER BY n.next_attempt_at, n.id
      LIMIT p_limit
      FOR UPDATE SKIP LOCKED
    ),
    claimed AS (
      UPDATE public.closure_notifications n
      SET status = 'sending', attempts = n.attempts + 1,
          locked_until = now() + make_interval(secs => p_lease_seconds)
      FROM due WHERE n.id = due.id
      RETURNING n.*
    )
    SELECT c.id, c.kind, c.attempts, r.review_status, r.cause, r.closure_reason, r.event_title,
      r.event_start_time, r.event_end_time, c.follow_up_message,
      CASE WHEN c.kind = 'admin_review' THEN (
        SELECT jsonb_agg(jsonb_build_object('review_id', b.id, 'title', b.event_title,
          'start_time', b.event_start_time, 'end_time', b.event_end_time,
          'host_first_name', b.host_first_name, 'host_last_name', b.host_last_name,
          'cause', b.cause, 'closure_reason', b.closure_reason, 'review_status', b.review_status)
          ORDER BY b.event_start_time, b.id)
        FROM public.closure_reviews b WHERE b.batch_id = c.batch_id) END,
      coalesce(u.email, r.host_email), coalesce(u.first_name, r.host_first_name),
      coalesce(u.last_name, r.host_last_name)
    FROM claimed c
    LEFT JOIN public.closure_reviews r ON r.id = c.review_id
    LEFT JOIN public.users u ON u.id = c.recipient_user_id
    ORDER BY c.next_attempt_at, c.id;
END;
$$;

ALTER TABLE public.reservation_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.closure_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.closure_notifications ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS deny_direct_api_access ON public.reservation_overrides;
DROP POLICY IF EXISTS deny_direct_api_access ON public.closure_reviews;
DROP POLICY IF EXISTS deny_direct_api_access ON public.closure_notifications;
CREATE POLICY deny_direct_api_access ON public.reservation_overrides
  AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
CREATE POLICY deny_direct_api_access ON public.closure_reviews
  AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
CREATE POLICY deny_direct_api_access ON public.closure_notifications
  AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);

-- The backend reads these tables. Audit and review rows change only through the functions below,
-- and the only columns it can update are the ones that track delivery.
REVOKE ALL ON public.reservation_overrides, public.closure_reviews, public.closure_notifications
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON SEQUENCE public.reservation_overrides_id_seq, public.closure_reviews_id_seq,
  public.closure_notifications_id_seq FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON public.reservation_overrides, public.closure_reviews, public.closure_notifications TO service_role;
GRANT UPDATE (status, attempts, next_attempt_at, locked_until, last_error, sent_via, sent_at)
  ON public.closure_notifications TO service_role;

REVOKE ALL ON FUNCTION public.require_enabled_admin(integer),
  public.queue_closure_cancellations(integer[], date[], integer, text)
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.create_special_reservation(integer, text, jsonb),
  public.mark_closure_review(integer, bigint, text),
  public.queue_closure_follow_up(integer, bigint, text),
  public.claim_closure_notifications(integer, integer)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_special_reservation(integer, text, jsonb),
  public.mark_closure_review(integer, bigint, text),
  public.queue_closure_follow_up(integer, bigint, text),
  public.claim_closure_notifications(integer, integer)
  TO service_role;

NOTIFY pgrst, 'reload schema';
COMMIT;
