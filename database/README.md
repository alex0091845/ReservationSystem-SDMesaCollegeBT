# Database migrations

SQL changes to the Supabase PostgreSQL database live in `migrations/`, named
`YYYYMMDD_NNN_description.sql`, each with a matching `.rollback.sql`. Apply them in name order.
Each file runs in a single transaction, so a failure leaves the database unchanged.

| Migration | What it adds |
|---|---|
| `20261021_001_semesters_unavailable_days` | Semesters, unavailable (closed) days, the override audit trail, and database-enforced calendar rules on reservations |

## Before you apply anything

- **Never apply a migration to the shared or production Supabase project without team sign-off.**
  Try it on the `reservation-system-test` project (see `docs/LOCAL_TESTING.md`) or on a local
  PostgreSQL 16 first. The backend test suite does this automatically in a throwaway Docker
  container (`cd backend && mvn clean verify`).
- Take a backup (Supabase Dashboard → Database → Backups), or at least export `events`.
- Deploy order: **apply the migration first, then deploy the backend that uses it.** The new
  endpoints need the new tables and functions.

## Applying `20261021_001_semesters_unavailable_days`

The migration only adds things; it does not change existing rows. One behaviour change matters
right away: **once it is applied, every new or moved reservation must fall inside a semester.** With
no semester configured, every booking is refused with `409`. Create the current semester in the
same sitting.

1. Open the Supabase SQL Editor for the target project.
2. Paste and run `migrations/20261021_001_semesters_unavailable_days.sql`.
3. Immediately create the current (and next) semester, either through
   `POST /api/semesters` as an admin, or in SQL:

   ```sql
   INSERT INTO public.semesters (name, start_date, end_date)
   VALUES ('Fall 2026', '2026-08-24', '2026-12-19');
   ```

4. Verify:

   ```sql
   -- tables, RLS, and policies
   select tablename, policyname, permissive, roles, cmd
   from pg_policies
   where schemaname = 'public'
     and tablename in ('semesters', 'unavailable_days', 'calendar_override_audit');

   -- triggers on events
   select tgname from pg_trigger
   where tgrelid = 'public.events'::regclass and not tgisinternal;
   -- expect events_calendar_lock, events_calendar_rules_on_insert, events_calendar_rules_on_update

   -- anon/authenticated must have no access to the new tables
   select c.relname,
          has_table_privilege('anon', c.oid, 'SELECT') as anon_select,
          has_table_privilege('authenticated', c.oid, 'SELECT') as authenticated_select
   from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public'
     and c.relname in ('semesters', 'unavailable_days', 'calendar_override_audit');
   ```

5. Deploy the backend, then check through the API: `GET /api/semesters` returns the semester, a
   faculty booking inside it succeeds, and one outside it returns `409`.

PostgREST picks up the new tables and functions through the `NOTIFY pgrst, 'reload schema'` at the
end of the migration. If `rpc/...` calls return 404 right afterwards, run that statement again.

Calendar writes (reservations, closures, semester changes) must run under READ COMMITTED, the
default for PostgREST and the SQL Editor. Under any other isolation level they are refused, because
the race protection depends on seeing rows committed while waiting for the calendar lock.

Existing reservations that fall outside every semester, or on a day closed later, are left alone.
They can still be edited as long as their date does not change.

## Rolling back

`migrations/20261021_001_semesters_unavailable_days.rollback.sql` removes everything the migration
added and restores the previous behaviour. It **permanently deletes all semesters, unavailable
days, and the override audit trail**, so export them first. Reservations are not touched. Deploy a
backend without the semester and unavailable-day endpoints before or together with the rollback.

## Error codes raised by the database

The backend maps these to HTTP responses (`CalendarRuleErrors.java`):

| SQLSTATE | Meaning | HTTP |
|---|---|---|
| `BTC01` | Reservation on an unavailable day or outside every semester | 409 |
| `BTC02` | Closing a day that has reservations, without an admin override | 409 |
| `BTC03` | Semester change would strand upcoming reservations or its closures | 409 |
| `BTC04` | Closure date not inside a (matching) semester | 409 |
| `BTC05` | Closure date in the past | 400 |
| `BTA01` | Override attempted by someone who is not an enabled admin | 403 |
