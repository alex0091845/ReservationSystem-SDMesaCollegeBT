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

## Evidence from the database

The supplied Supabase privilege query showed that `anon` and `authenticated` had SELECT, INSERT,
UPDATE, and DELETE table privileges on `attendees`, `event_types`, `events`, `reservation_drafts`,
`user_roles`, and `users`. RLS was disabled for `attendees`, `events`, `user_roles`, and `users`.
RLS was enabled on `event_types` and `reservation_drafts`, but the screenshot did not show their
policies. `sessions` had RLS enabled and no listed table privileges for those roles.

The query did not establish whether any browser code used those direct privileges. A repository
search found no frontend Supabase client or direct Supabase REST request. It did establish that the
database API roles had privileges that were broader than this backend-only architecture needs.

## Controls in this branch

| Area | Change |
|---|---|
| Direct database access | The SQL migration provided in the implementation handoff revokes table, sequence, function, and schema-create privileges from `PUBLIC`, `anon`, and `authenticated`; removes existing policies on the seven application tables; enables RLS; and removes default grants for new objects created by `postgres`. |
| Private event data | The backend returns full details only to faculty and admin sessions. Other viewers receive an event ID, time interval, public flag, and generic title. Host identity and descriptive fields are removed. |
| Attendee records | Reads, updates, and deletes require faculty or admin. Public check-in input is field-whitelisted and its text fields are length-limited. |
| Account status | Disabled accounts cannot log in. Existing sessions are rejected when the account is disabled or missing. |
| Session tokens | The browser receives a random 256-bit token. The database stores only its SHA-256 hash. Applying the migration removes old raw session tokens and signs users out. |
| Request origin | CORS accepts exact configured origins. Wildcards and URL paths are rejected. State-changing requests with a non-allowlisted `Origin` or `Sec-Fetch-Site: cross-site` are rejected. |
| Database key | New `sb_*` keys are sent in `apikey`, not as a Bearer token. The current `sb_secret` key stays on EC2 and bypasses RLS, so backend authorization remains required. |
| Error responses | Database error bodies are not returned to callers. API responses include `Cache-Control: no-store`, `X-Content-Type-Options`, `X-Frame-Options`, and `Referrer-Policy` headers. |
| Dependencies | Updated the backend to Spring Boot 4.1.1 and moved the application to its managed Jackson 3 packages. |
| Regression checks | Added automated checks for private-event masking, staff access, disabled accounts, token hashing, origin checks, and secret-key headers. |

## Database migration

Paste the SQL migration provided in the implementation handoff into the Supabase SQL Editor and
run it once as the project database owner. It is designed for this backend-only data path. It
removes direct API policies and grants, enables RLS, and deletes all existing `sessions` rows. Users
will need to sign in again. Do not apply it if another trusted application depends on direct `anon`
or `authenticated` table access without first moving that traffic through an authorized service.

After applying the migration, run this query in the SQL Editor. Every table should report RLS as
enabled and all listed table privileges as false.

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

Confirm that no policies remain on the application tables:

```sql
select tablename, policyname
from pg_policies
where schemaname = 'public'
  and tablename = any (array[
    'attendees', 'event_types', 'events', 'reservation_drafts',
    'sessions', 'user_roles', 'users'
  ]);
```

Expected result: zero rows. A PostgREST request made with a publishable key and no user session
should not be able to read or write these tables. Do not use or paste the secret key to run this
check.

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
