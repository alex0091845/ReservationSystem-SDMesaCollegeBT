-- Rollback for admin_override_notifications.sql. Run this, then straight after it the version of
-- reservation_availability.sql from before this feature (git show 811210c:backend/sql/reservation_availability.sql),
-- which restores the four-argument save and the original availability trigger.
-- Between the two, availability saves and bookings on closed dates are refused; nothing else changes.
--
-- WARNING: this permanently deletes the override audit trail, the closure reviews, and the
-- notification history, including any emails not sent yet. Export them first if they matter.
-- Special reservations stay in events, and the next availability save will treat any on a closed
-- date as reservations to cancel. Deploy a backend without this feature before or together with it.
BEGIN;

LOCK TABLE public.events IN SHARE ROW EXCLUSIVE MODE;

DROP FUNCTION IF EXISTS public.claim_closure_notifications(integer, integer);
DROP FUNCTION IF EXISTS public.queue_closure_follow_up(integer, bigint, text);
DROP FUNCTION IF EXISTS public.mark_closure_review(integer, bigint, text);
DROP FUNCTION IF EXISTS public.save_reservation_availability(date, date[], bigint, integer[], integer, text);
DROP FUNCTION IF EXISTS public.queue_closure_cancellations(integer[], date[], integer, text);
DROP FUNCTION IF EXISTS public.create_special_reservation(integer, text, jsonb);
DROP FUNCTION IF EXISTS public.require_enabled_admin(integer);

DROP TABLE IF EXISTS public.closure_notifications;
DROP TABLE IF EXISTS public.closure_reviews;
DROP TABLE IF EXISTS public.reservation_overrides;

NOTIFY pgrst, 'reload schema';
COMMIT;
