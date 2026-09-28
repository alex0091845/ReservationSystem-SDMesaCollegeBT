# Backend Handover

Everything you need to take over the Spring Boot API. Assumes you know Java but nothing about this project.

**Looking for the endpoints themselves** — request bodies, responses, error codes? That's
[`API.md`](API.md). This document is about how the backend is built and how to work on it.

---

## 1. What the backend actually is

It is a **REST API in front of Supabase**. The browser calls the CloudFront distribution; its
`/api/*` behavior forwards those requests to this service on EC2. The backend calls Supabase
PostgREST using a server-only API key.

There is **no JPA, no Hibernate, no `@Entity` classes, no repositories, no SQL in Java**. Instead:

```
Browser --HTTPS--> CloudFront --/api/*--> Spring Boot (EC2) --HTTPS--> Supabase PostgREST --> PostgreSQL
                                        :8080 /api/...       /rest/v1/<table>?<filters>
```

Every controller builds a **PostgREST query string** (e.g. `events?id=eq.5&select=*`), hands it to
`SupabaseClient`, and returns the raw JSON string it gets back. Data is passed around as Jackson
`JsonNode`, not as typed model objects.

**Why it matters:** if you want to change what data comes back, you edit a *query string*, not a
SQL file or an entity class. PostgREST syntax is documented at <https://postgrest.org/en/stable/references/api/tables_views.html>.

The backend's real jobs are the four things Supabase can't do for us:

1. **Sessions / login** — cookie-based auth (`session_id`).
2. **Authorization** — who may read/write/delete what.
3. **Input whitelisting** — never forward raw client JSON to the database.
4. **Honest error reporting** — especially telling the client when a delete was *refused*.

---

## 2. Running it locally

```bash
cd backend

export SUPABASE_URL="https://xxxx.supabase.co"
export SUPABASE_API_KEY="<server-only sb_secret key>"
export APP_CORS_ALLOWED_ORIGIN_PATTERNS="http://localhost:5500"
export AUTH_COOKIE_SECURE=false        # required for plain http on localhost

mvn spring-boot:run
```

Check it is alive:

```bash
curl http://localhost:8080/api/health     # → {"status":"ok"}
```

Requirements: Java 17+ and Maven. Run the automated checks with `mvn clean verify`, then build
the jar at `target/*.jar`.
There is also a multi-stage `backend/Dockerfile` (builds with Maven, runs on a JRE image as a
non-root `appuser`).

### Environment variables

Defined in `src/main/resources/application.properties`.

| Variable | Required | Default | Notes |
|---|:--:|---|---|
| `SUPABASE_URL` | ✅ | — | Supabase project URL |
| `SUPABASE_API_KEY` | ✅ | None | The current EC2 configuration uses an `sb_secret` key. It is server-only and bypasses RLS, so every API authorization check must be enforced by the backend. New `sb_*` keys are sent in the `apikey` header only. |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | ✅ | None | Comma-separated frontend origins. **No default on purpose:** the app refuses to start if unset, so a misconfigured deploy can never silently allow all origins with credentials |
| `SERVER_PORT` | | `8080` | |
| `AUTH_COOKIE_SECURE` | | `true` | `false` only for local http |
| `AUTH_COOKIE_SAME_SITE` | | `Lax` | `Lax` works for the current CloudFront same-origin `/api/*` route. `None` requires `AUTH_COOKIE_SECURE=true` for direct cross-site API requests. |
| `AUTH_SESSION_HOURS` | | `8` | Session lifetime |

### EC2 environment file

The production EC2 environment file is `/etc/reservation-backend.env`. Keep it on the server;
do not add it to the repository or container image. To check which environment file the systemd
service loads without printing its values, run:

```bash
sudo systemctl show reservation-backend -p FragmentPath -p DropInPaths -p EnvironmentFiles
```

The service unit must reference the file with `EnvironmentFile=/etc/reservation-backend.env` for
systemd to load it.

Error detail is suppressed globally (`server.error.include-message=never`, no stack traces) so
Supabase URLs and internals never leak to a browser.

---

## 3. File map

```
backend/src/main/java/com/reservation/
├── RoomReservationApplication.java     main() — nothing else in it
├── config/
│   ├── SupabaseClient.java             ★ the only thing that talks to the database
│   ├── AuthInterceptor.java            ★ the entire authorization policy
│   ├── CorsConfig.java                 CORS rules + registers the interceptor
│   ├── PasswordEncoderConfig.java      one shared BCrypt bean
│   └── GlobalExceptionHandler.java     turns any uncaught exception into a safe JSON error
├── services/
│   ├── AuthService.java                find user by email/id, check password, strip password_hash
│   └── SessionService.java             create / read / invalidate sessions, build the cookie
└── controller/
    ├── AuthController.java             /api/login, /api/logout, /api/session
    ├── HealthController.java           /api/health
    ├── ReservationController.java      ★ /api/events — the biggest and most important file
    ├── ReservationDraftController.java /api/reservation-drafts — autosaved form drafts
    ├── AttendeeController.java         /api/attendees — event check-ins
    ├── EventTypeController.java        /api/event-types
    ├── UserController.java             /api/users
    ├── UserRoleController.java         /api/roles
    └── DeleteOutcome.java              shared helper for DELETE responses (not a controller)
```

Start reading in this order: `SupabaseClient` → `AuthInterceptor` → `ReservationController`.
After those three, the rest is repetition of the same patterns.

---

## 4. The pieces, one by one

### `SupabaseClient` — the database layer

Wraps a plain JDK `HttpClient`. Four methods: `get`, `post`, `patch`, `delete`. Each sends the
`apikey` header to `${supabase.url}/rest/v1/<endpoint>`. New `sb_*` keys are not JWTs and are never
sent in `Authorization: Bearer`; legacy JWT keys are still sent in both headers for compatibility.

- 5-second connect timeout, 10-second per-request read timeout, so a hung Supabase call can't pin a
  servlet thread forever.
- `post`/`patch` send `Prefer: return=representation`, which makes PostgREST echo back the row it
  wrote — that's how controllers return the created/updated object.
- Writes come in two flavours: `post()` returns just the body `String`; `postResponse()` returns a
  `SupabaseResponse(statusCode, body)` record so the caller can see the status. Same for `patch`.
- `delete()` **only** returns the record — deletes must always be inspected (see below).

`SupabaseResponse` has two helpers:
- `isSuccessful()` — 2xx.
- `isForeignKeyViolation()` — parses the JSON body looking for Postgres SQLSTATE `23503`, i.e.
  "another table still references this row."

### `AuthInterceptor`: request authorization

Registered in `CorsConfig` against `/api/**`. It runs before every controller and does three things:

1. **Lets some requests through without a session** (`isPublicEndpoint`):
   - `/api/login`, `/api/logout`, `/api/session`, `/api/health`
   - **any `GET`** on `/api/events`, `/api/event-types`, `/api/roles`
   - `POST /api/attendees` (public check-in — anyone attending an event can sign in without an account)
   - all `OPTIONS` requests (CORS preflight)
2. For event reads, resolves a presented session so the controller can decide whether to show
   private details. Without a `faculty` or `admin` role, private events are masked server-side.
3. Other protected requests resolve the session cookie; **401** if there is no active session. The
   user is stored as request attribute `currentUser` for ownership checks.
4. Applies role rules; **403** if they fail:
   - everything under `/api/users` is admin-only, **including GET**
   - non-GET on `/api/roles` and `/api/event-types` is admin-only
   - attendee reads, updates, and deletes require faculty or admin
   - state-changing requests with an unapproved `Origin` or `Sec-Fetch-Site: cross-site` are rejected

Creating, editing, and deleting reservations requires a valid session and controller-level
ownership checks. Public event reads retain only the event ID, start time, end time, public flag,
and generic title for private events. The schedule interval remains visible so the calendar can
show the room is occupied.

### `AuthService` and `SessionService` — login

`AuthService`:
- `authenticate(email, password)` — looks the user up, verifies with BCrypt, returns a **sanitized**
  copy (`password_hash` removed).
- Disabled accounts cannot log in. Existing sessions stop working on the next authenticated
  request. Email is normalized to lowercase, and passwords over 72 UTF-8 bytes are rejected.
- Password check **fails closed**: if the stored value isn't a `$2a$`/`$2b$`/`$2y$` BCrypt hash it is
  never a match, so a legacy plaintext password can't be used to log in.
- `isFacultyOrAdmin(user)` recognizes the `faculty` and `admin` roles for private event details
  and attendee records. `isAdmin(user)` checks whether the role is `"admin"`, case-insensitive. Role can arrive as `role_name`,
  `role`, or nested `user_roles.name`; `getRoleName` tries all three.

`SessionService`:
- On login, generates 32 random bytes from `SecureRandom`, base64url-encodes them for the cookie,
  stores only their SHA-256 hash in `sessions`, and returns an `HttpOnly` cookie.
- On every authenticated request, `getCurrentUser` reads the cookie, loads the session row, rejects
  it if `invalidated_at` is set or `expires_at` has passed (invalidating it on the way out), then
  updates `last_seen_at` and returns the user.
- Logout sets `invalidated_at` and returns a cookie with `maxAge=0`.

Cookie flags come from config: `HttpOnly` always, `Secure` and `SameSite` from env vars.
The service rejects invalid SameSite values, requires `Secure` for `SameSite=None`, and limits the
configured session duration to 1 through 24 hours.

The database migration deletes existing raw session tokens. Everyone will need to sign in again
after it is applied. Expired sessions still need a scheduled cleanup policy.

### `ReservationController` — `/api/events`

The core file. Two things dominate it: **normalization** and **ownership**.

**Normalization** (`normalizeEventInput`) builds a fresh `ObjectNode` copying only the nine known
columns — nothing a client sends outside that list ever reaches the database. It also accepts
aliases (`user_id` → `host_user_id`, `start` → `start_time`, `end` → `end_time`) and coerces
strings to numbers/booleans. Then `validateEventBody` requires all eight core fields and enforces
the booking window in **`America/Los_Angeles`**:

- end must be after start
- start must be before **5:00 PM**
- end must not be after **5:00 PM**

Timestamps are parsed leniently — ISO instant, then offset date-time, then local date-time.

**Ownership**: For edits and deletes, `canModifyExistingEvent` allows admins to act on any event
and faculty to act only when the event's stored `host_user_id` matches their user ID. Other roles
cannot edit or delete events, even if their ID appears as the host. A faculty owner can update the
event's data, including its `event_type`. The shared event-type catalog remains admin-only.
`canSaveWithRequestedHost` also prevents faculty from assigning an event to another host.

Endpoints:

| Method | Path | Notes |
|---|---|---|
| GET | `/api/events` | Public schedule; private details are masked unless caller is faculty/admin |
| GET | `/api/events/{id}` | Same private-field masking rules |
| GET | `/api/events/by-user/{userId}` | Same private-field masking rules |
| GET | `/api/events/public` | `is_public=true` only |
| POST | `/api/events` | one reservation |
| POST | `/api/events/batch` | array of reservations, one insert |
| POST | `/api/events/recurring` | see below |
| PATCH | `/api/events/{id}` | |
| DELETE | `/api/events/{id}` | see below |

**`POST /api/events/recurring`** is a bandwidth optimisation. Instead of the browser sending 16
nearly identical reservations, it sends one template plus a compact description of the repeats:

```json
{
  "event": { "host_user_id": 3, "title": "Office Hours", "...": "..." },
  "ranges": [
    { "start_time": "...", "end_time": "...", "week_count": 16, "excluded_week_offsets": [5, 11] }
  ]
}
```

The controller expands each range week by week (`baseStart.plusWeeks(n)`), skipping excluded
offsets, validates every occurrence, and inserts them all in a single Supabase POST. Validation
errors name the exact range and week so the user gets a useful message. Excluding *every* week is
rejected.

**`DELETE /api/events/{id}`** has a wrinkle worth knowing. The `attendees.event_id` foreign key has
no `ON DELETE` rule, so Postgres refuses to delete an event that has check-ins. The controller
therefore deletes the attendee rows first — deleting a reservation is taken to discard its
check-in records with it — and if *that* fails it returns 502 and leaves the reservation alone.
Only then does it delete the event, via `DeleteOutcome`.

### `DeleteOutcome` — why deletes are special

Every DELETE endpoint routes through this helper. The reason is a bug class that already bit this
project: PostgREST answers a *refused* delete with a 4xx and a JSON body. If you return `204 No
Content` without looking at that response, you tell the client the row is gone when it is still in
the table — the UI removes it from view and it reappears on the next page load.

```java
if (response.isSuccessful())        return 204 No Content;
if (response.isForeignKeyViolation()) return 409 + a message naming what still references it;
return 502 "The delete could not be completed. Please try again.";
```

Each caller passes its own 409 message ("Reservations are still using this event type…",
"This user still has reservations or sessions on record…"). **If you add a DELETE endpoint, use
this helper.**

### `ReservationDraftController` — `/api/reservation-drafts`

Server-side autosave for half-finished reservation forms, so a user who closes a modal or switches
device doesn't lose their work.

- A draft is `draft_type` (`"create"` or `"edit"`) + an arbitrary JSON `payload`. Edit drafts also
  carry `source_event_id`.
- **Every query is scoped to `user_id = currentUser.id`.** You can never read or write another
  person's draft. Edit drafts additionally check that you own (or are admin of) the source event.
- Saving is an upsert: it looks for an existing non-discarded draft with the same
  (user, type, source event) and PATCHes it, otherwise POSTs a new one. A partial unique index in
  `erd.sql` enforces one active draft per combination.
- Deleting removes the draft row. The endpoint checks the database response and confirms the
  row is absent before returning 204. Existing soft-discarded rows remain until cleaned up.

### `UserController` — `/api/users` (admin only)

- `buildUserPayload` whitelists `email`, `first_name`, `last_name`, `phone`, `role_name`, `enabled`.
- **A client can never set `password_hash` directly.** Only a `password` field is accepted, and it
  is BCrypt-hashed here on the server.
- On create, email and password are required. On update, a blank password means "keep the current
  one" (the frontend simply omits the field).
- New or changed passwords must be 12 to 72 UTF-8 bytes.
- `sanitizeUserResponse` walks the whole response tree and strips `password_hash` before returning,
  belt-and-braces with `AuthService`.
- There is **no hard delete of users from the app** — the UI disables accounts by setting
  `enabled=false`. A `DELETE /api/users/{id}` exists and will return 409 if the account still has
  reservations or sessions.

### `AttendeeController` — `/api/attendees`

Check-ins. `POST` is **public and unauthenticated** because attendees do not have accounts. Reads,
updates, and deletes require a faculty or admin session. Because raw client JSON must never reach
PostgREST on a public endpoint,
`buildAttendeePayload` whitelists `event_id`, `sdccd_id`, `first_name`, `last_name`, `email`, and
lets `check_in_time` fall back to the table default `now()`. `event_id` and `first_name` are
required on create. Text fields are length-limited.

### `EventTypeController` / `UserRoleController`

GET is public and writes are admin-only. Both controllers whitelist request fields before they
reach PostgREST. Role routes use the actual `user_roles.name` primary key.

### `GlobalExceptionHandler`

- `IllegalArgumentException` → **400** with the message (that's how validation failures surface).
- Anything else → **500 `{"error":"Internal server error"}`**, deliberately generic. Never add
  `e.getMessage()` here; it would leak Supabase hosts and transport errors to the browser.

---

## 5. Endpoint map

> Quick orientation only. For request bodies, response shapes, every error message, curl examples
> and per-endpoint caveats, see **[`API.md`](API.md)**.

Auth column: **public** = no login needed; **login** = any authenticated user; **faculty/admin** =
staff role required; **admin** = admin role required. Enforced in `AuthInterceptor`; ownership
checks marked ★ happen inside the controller.

| Method | Path | Auth | Notes |
|---|---|:--:|---|
| GET | `/api/health` | public | liveness probe |
| POST | `/api/login` | public | sets the `session_id` cookie |
| POST | `/api/logout` | public | invalidates the session, clears the cookie |
| GET | `/api/session` | public | 401 when there is no valid session |
| GET | `/api/events` | public | Private details are masked unless caller is faculty/admin |
| GET | `/api/events/{id}` | public | |
| GET | `/api/events/by-user/{userId}` | public | |
| GET | `/api/events/public` | public | `is_public=true` only; currently unused |
| POST | `/api/events` | login ★ | |
| POST | `/api/events/batch` | login ★ | array body |
| POST | `/api/events/recurring` | login ★ | template + week ranges |
| PATCH | `/api/events/{id}` | faculty owner/admin ★ | |
| DELETE | `/api/events/{id}` | faculty owner/admin ★ | deletes the event's attendees first |
| GET | `/api/reservation-drafts` | login | always scoped to the caller |
| POST | `/api/reservation-drafts` | login ★ | upsert |
| DELETE | `/api/reservation-drafts/{id}` | login | permanently deletes the caller's draft |
| GET | `/api/attendees` | faculty/admin | Contains attendee personal information |
| GET | `/api/attendees/{id}` | faculty/admin | Contains attendee personal information |
| GET | `/api/attendees/by-event/{eventId}` | faculty/admin | Contains attendee personal information |
| POST | `/api/attendees` | **public** | the public check-in |
| PATCH | `/api/attendees/{id}` | faculty/admin | |
| DELETE | `/api/attendees/{id}` | faculty/admin | |
| GET | `/api/event-types` | public | |
| GET | `/api/event-types/{eventType}` | public | |
| POST / PATCH / DELETE | `/api/event-types…` | admin | request fields are whitelisted |
| GET | `/api/roles` | public | |
| GET | `/api/roles/{name}` | public | `name` is the primary key |
| PATCH / DELETE | `/api/roles/{name}` | admin | `name` is the primary key |
| POST | `/api/roles` | admin | whitelisted request body |
| GET / POST / PATCH / DELETE | `/api/users…` | admin | GET is admin-only too |

Verified against a running instance: `/api/health` → 200; `GET /api/users`, `/api/attendees`,
`/api/reservation-drafts`, `DELETE /api/events/1` → 401 without a cookie; `POST /api/attendees`
reaches validation without a cookie.

---

## 6. Database

Schema lives in `erd.sql` at the repo root; apply it in the Supabase SQL editor.

| Table | Key columns |
|---|---|
| `users` | `id`, `email` (unique), `password_hash`, `role_name` → `user_roles`, `enabled` |
| `user_roles` | **`name` is the primary key** — there is no `id` column |
| `events` | `id`, `host_user_id` → `users`, `start_time`, `end_time`, `event_type` → `event_types`, `title`, `description`, `department`, `is_public`, `recurrence_group_id` |
| `event_types` | `event_type` is the primary key |
| `attendees` | `id`, `event_id` → `events` (**no ON DELETE rule**), `sdccd_id`, name/email, `check_in_time` |
| `sessions` | `session_id` (unique SHA-256 token hash), `user_id`, `expires_at`, `invalidated_at`, `last_seen_at` |
| `reservation_drafts` | `user_id`, `draft_type` (`create`/`edit`), `source_event_id`, `payload` jsonb, `discarded_at` |

`recurrence_group_id` is a free-text UUID generated by the **browser**; every occurrence of a
recurring booking shares one, which is how "delete the whole series" works.

`reservation_drafts` has a partial unique index guaranteeing one active draft per
(user, draft_type, source_event_id).

---

## 7. Common tasks

**Add a field to reservations**
1. `ALTER TABLE` in Supabase.
2. Add a `copyField(...)` line in `ReservationController.normalizeEventInput` — *without this the
   field is silently dropped*.
3. Add it to `validateEventBody` only if it's required.
4. Add it to `toBackendEvent` in `frontend/js/api.js`.

**Add a new endpoint**
1. New method in the relevant controller. Read the caller with
   `request.getAttribute("currentUser")`.
2. Decide its auth: if it should be public, whitelist it in `AuthInterceptor.isPublicEndpoint`;
   if admin-only, add it to `requiresAdmin`. **Doing nothing means "any logged-in user."**
3. For DELETE, return through `DeleteOutcome.toResponse(...)`.

**Debug a request**
`curl -i -c jar -X POST localhost:8080/api/login -H 'Content-Type: application/json' -d '{"email":"…","password":"…"}'`
then reuse the cookie jar with `-b jar`. Remember that a generic 500 means the detail is in the
**server log**, not the response — that's by design.

---

## 8. Security rollout and remaining work

This branch adds backend controls and documents a database migration. The production Supabase project and
CloudFront distribution still need an operator to apply or verify the items below. This checkout
has not changed those live services.

### Apply the database migration

Paste the SQL migration provided in the implementation handoff into the Supabase SQL Editor and
run it once as the project database owner. It revokes public, anon, and authenticated grants on the
application's tables and sequences, removes existing policies, enables RLS, blocks future default
grants for the `postgres` owner, and deletes current session rows. All users will need to sign in
again. The server-only key bypasses RLS, so the backend must enforce all caller authorization rules.

After applying it, verify that the seven application tables report RLS enabled, every `anon_*` and
`authenticated_*` table privilege is false, and the policy query returns no rows for those tables.
Use the verification query in [`SECURITY.md`](SECURITY.md).

### Verify AWS and runtime settings

- Confirm the CloudFront `/api/*` behavior uses the EC2 origin, disables caching, forwards viewer
  cookies, and uses HTTPS to the origin. The supplied screenshots confirm the EC2 origin and
  `Managed-AllViewerExceptHostHeader` policy. Confirm the origin protocol and EC2 ingress rules in
  AWS because those settings were not shown.
- Restrict the EC2 backend port so it accepts traffic from CloudFront, not the public internet.
- Add a CloudFront response headers policy for the static frontend and a rate-based WAF rule for
  login and public check-in requests. These AWS resources are outside this repository and have not
  been applied.
- Keep `/etc/reservation-backend.env` readable only by root. Never paste or commit its values. Use
  a separate key for this backend, and rotate it if the full key was exposed.
- After deployment, confirm anonymous table REST requests are denied, public event responses do not
  contain private fields, and faculty/admin sessions can read the intended records.

### Known limitations

- Private event start and end times remain in the public calendar response so the UI can show when
  the room is occupied. Names, titles, descriptions, departments, event types, host IDs, and
  recurrence IDs are removed for viewers without a faculty or admin session.
- Expired and invalidated session rows still need a scheduled cleanup policy.
- The backend still needs overlap validation and enforcement of the 8 AM opening time. These
  reservation integrity controls remain server-side gaps.
- Local tests cannot prove database grants or AWS behavior until the migration and runtime checks
  are completed against production services.

---

## 9. Glossary

- **PostgREST** — the service that turns Supabase tables into a REST API. `?id=eq.5` means
  `WHERE id = 5`; `?select=*,users(first_name)` is a join.
- **`JsonNode` / `ObjectNode`** — Jackson's untyped JSON tree. This codebase uses it instead of DTOs.
- **`@Component` / `@Service` / `@RestController`** — "Spring, create one of these at startup and
  inject it where needed." Constructor injection is used throughout.
- **Interceptor** — Spring MVC's before-the-controller hook. `AuthInterceptor` is the gate.
- **SQLSTATE 23503** — the Postgres error code for a foreign-key violation.
