# Security notes and rollout plan

This document records the security controls in the repository, the evidence used to choose them,
and the production steps that still require access to Supabase or AWS. It is also a record of the
system's current trust boundaries for future security reviews.

## System boundary

The browser loads the static frontend from CloudFront. The distribution's `/api/*` behavior sends
API requests to the EC2 backend, and the default behavior sends static files to S3. The backend
uses a Supabase server key to call PostgREST. The browser code does not call Supabase directly.

The supplied CloudFront screenshots show the EC2 backend origin, an `/api/*` behavior with caching
disabled, and the `Managed-AllViewerExceptHostHeader` origin request policy. The browser's API URL
is the CloudFront origin, so its API calls are same-origin. The screenshots do not show the
CloudFront-to-EC2 origin protocol policy, EC2 security group rules, WAF association, or deployed
response headers policy.

## Local testing boundary

The local launcher serves the existing frontend and backend source through a local `/api` proxy.
It targets the test Supabase project and sets local-only cookie and CORS values. Use a test project's
server-only key, never the production key. The launcher keeps the key out of the frontend proxy and
browser process and passes it to the backend process at startup. See [`LOCAL_TESTING.md`](LOCAL_TESTING.md)
for setup and key handling. The ignored settings file is plain text if a key is saved there; Git
ignoring the file does not encrypt it.

## Evidence from the database

The initial Supabase privilege query showed that `anon` and `authenticated` had SELECT, INSERT,
UPDATE, and DELETE table privileges on most application tables. RLS was disabled on four of the
seven tables. The latest Supabase query results shared for this project show RLS enabled on all
seven tables and one restrictive `deny_direct_api_access` policy per table, for `anon` and
`authenticated`, with `cmd = ALL`, `qual = false`, and `with_check = false`.

The latest screenshots do not include a fresh table-grant query or a live request test. The policy
script does not change table grants. A repository search found no frontend Supabase client or
direct Supabase REST request. The browser calls the Java backend, which uses a server-only key.

## Controls in this branch

| Area | Change |
|---|---|
| Direct database access | The initial SQL migration revokes direct API grants and enables RLS. A follow-up script adds a restrictive deny policy for `anon` and `authenticated` on all seven application tables. Current screenshots verify the RLS flags and policies, but not current grants or live request behavior. |
| Private event data | The backend returns full details only to faculty and admin sessions. Other viewers receive an event ID, time interval, public flag, and generic title. Host identity and descriptive fields are removed. |
| Attendee records | Reads, updates, and deletes require faculty or admin. Public check-in input is field-whitelisted and its text fields are length-limited. |
| Account status | Disabled accounts cannot log in. Existing sessions are rejected when the account is disabled or missing. |
| Session tokens | The browser receives a random 256-bit token. The database stores only its SHA-256 hash. Applying the migration removes old raw session tokens and signs users out. |
| Request origin | CORS accepts exact configured origins. Wildcards and URL paths are rejected. State-changing requests with a non-allowlisted `Origin` or `Sec-Fetch-Site: cross-site` are rejected. |
| Database key | New `sb_*` keys are sent in `apikey`, not as a Bearer token. The current `sb_secret` key stays on EC2 and bypasses RLS, so backend authorization remains required. |
| Error responses | Database error bodies are not returned to callers. API responses include `Cache-Control: no-store`, `X-Content-Type-Options`, `X-Frame-Options`, and `Referrer-Policy` headers. |
| Dependencies | Updated the backend to Spring Boot 4.1.1 and moved the application to its managed Jackson 3 packages. |
| Regression checks | Added automated checks for private-event masking, staff access, disabled accounts, token hashing, origin checks, and secret-key headers. |

## Database changes

The initial database migration was reported as applied. It removes direct API access, enables RLS,
and deletes existing `sessions` rows, so users had to sign in again. The follow-up RLS policy script
was also run. It does not alter grants or session rows. These controls are intended for the current
backend-only architecture. Another trusted application that accesses Supabase directly would need
its own authorized access design before these deny policies are changed.

The follow-up policy SQL, in a form that can be rerun, is:

```sql
BEGIN;

DO $rls$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'attendees',
        'event_types',
        'events',
        'reservation_drafts',
        'sessions',
        'user_roles',
        'users'
    ]
    LOOP
        EXECUTE format(
            'ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',
            table_name
        );
        EXECUTE format(
            'DROP POLICY IF EXISTS deny_direct_api_access ON public.%I',
            table_name
        );
        EXECUTE format(
            'CREATE POLICY deny_direct_api_access ON public.%I
             AS RESTRICTIVE
             FOR ALL
             TO anon, authenticated
             USING (false)
             WITH CHECK (false)',
            table_name
        );
    END LOOP;
END
$rls$;

COMMIT;
```

To verify the policy definitions, run:

```sql
select tablename, policyname, permissive, roles, cmd, qual, with_check
from pg_policies
where schemaname = 'public'
  and tablename = any (array[
    'attendees', 'event_types', 'events', 'reservation_drafts',
    'sessions', 'user_roles', 'users'
  ])
order by tablename, policyname;
```

The expected result is one `RESTRICTIVE` `deny_direct_api_access` policy per table, with roles
`{anon,authenticated}`, command `ALL`, and both expressions set to `false`. The latest screenshots
show that result and show RLS enabled on all seven tables.

The earlier privilege query showed table grants to `anon` and `authenticated`. The follow-up policy
script did not revoke those grants, and the latest screenshots do not show whether the initial
migration changed them. Re-run the following query to verify their current state. Each privilege
should be false for both roles:

```sql
select
  c.relname as table_name,
  c.relrowsecurity as rls_enabled,
  has_table_privilege('anon', c.oid, 'SELECT') as anon_select,
  has_table_privilege('anon', c.oid, 'INSERT') as anon_insert,
  has_table_privilege('anon', c.oid, 'UPDATE') as anon_update,
  has_table_privilege('anon', c.oid, 'DELETE') as anon_delete,
  has_table_privilege('authenticated', c.oid, 'SELECT') as authenticated_select,
  has_table_privilege('authenticated', c.oid, 'INSERT') as authenticated_insert,
  has_table_privilege('authenticated', c.oid, 'UPDATE') as authenticated_update,
  has_table_privilege('authenticated', c.oid, 'DELETE') as authenticated_delete
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relname = any (array[
    'attendees', 'event_types', 'events', 'reservation_drafts',
    'sessions', 'user_roles', 'users'
  ])
order by c.relname;
```

The database owner can bypass RLS, so inspecting rows in the SQL Editor does not verify the deny
policies. For a behavioral check, use a publishable key and the Data API roles, never the server
secret key. Direct client requests should be denied. Separately, verify website event loading,
check-in, draft saving, and faculty/admin actions through the backend because its `sb_secret` key
bypasses RLS and its authorization checks remain essential.

## AWS checks before calling production secure

These are external settings. They are not changed by repository commits.

1. In CloudFront, verify `/api/*` uses the EC2 origin, caching remains disabled, viewer cookies are
   forwarded, and the origin protocol is HTTPS only.
2. In the EC2 security group, restrict the backend listener so public clients cannot bypass
   CloudFront. Confirm the selected CloudFront origin path and port still work after the change.
3. Associate a response headers policy with the static frontend behavior. Include a restrictive
   content security policy that fits the app, `X-Content-Type-Options: nosniff`, a frame restriction,
   a referrer policy, and HSTS for HTTPS.
4. Add a rate-based WAF rule for `/api/login` and `/api/attendees`. Choose and tune limits using
   expected legitimate traffic, since public attendee check-in must continue to work.
5. Confirm the distribution's `/api/*` behavior has no cached responses after deployment. The
   screenshot already showed the managed caching-disabled policy, but verify the live setting after
   any distribution update.

Keep `/etc/reservation-backend.env` out of source control and the container image. On EC2, check
file permissions without printing the values. Use a distinct Supabase secret key for this backend,
and rotate it if its full value was exposed. The key prefix alone is not the secret value.

## Verification completed in this checkout

`mvn clean verify` passed 10 backend tests. They use mocked Supabase responses and a local HTTP
server. They do not connect to the production database or AWS. Production access controls remain
unverified until the migration and AWS checks above are completed.

## Remaining backend work

- Schedule cleanup for expired and invalidated session rows.
- Add a database-level event-overlap constraint and enforce the 8 AM opening time on the server.
- Review public check-in abuse controls after the WAF rule is configured.
- Confirm whether exposing private-event occupied intervals is acceptable. The card details are
  hidden, but the calendar still reveals when a room is occupied.

## References

- [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys)
- [Supabase Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [Spring Boot supported versions and requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [CloudFront managed origin request policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-origin-request-policies.html)
