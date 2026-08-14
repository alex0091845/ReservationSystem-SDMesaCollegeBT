# Backend Handover

Everything you need to take over the Spring Boot API. Assumes you know Java but nothing about this project.

**Looking for the endpoints themselves** — request bodies, responses, error codes? That's
[`API.md`](API.md). This document is about how the backend is built and how to work on it.

---

## 1. What the backend actually is

It is a **thin REST API in front of Supabase**. That is the single most important thing to understand.

There is **no JPA, no Hibernate, no `@Entity` classes, no repositories, no SQL in Java**. Instead:

```
Browser  ──HTTP──▶  Spring Boot (EC2)  ──HTTPS──▶  Supabase PostgREST  ──▶  PostgreSQL
                    :8080 /api/...                  /rest/v1/<table>?<filters>
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
export SUPABASE_API_KEY="<anon key>"
export APP_CORS_ALLOWED_ORIGIN_PATTERNS="http://localhost:5500"
export AUTH_COOKIE_SECURE=false        # required for plain http on localhost

mvn spring-boot:run
```

Check it is alive:

```bash
curl http://localhost:8080/api/health     # → {"status":"ok"}
```

Requirements: Java 17+ and Maven. Build a jar with `mvn clean package` → `target/*.jar`.
There is also a multi-stage `backend/Dockerfile` (builds with Maven, runs on a JRE image as a
non-root `appuser`).

### Environment variables

Defined in `src/main/resources/application.properties`.

| Variable | Required | Default | Notes |
|---|:--:|---|---|
| `SUPABASE_URL` | ✅ | — | Supabase project URL |
| `SUPABASE_API_KEY` | ✅ | — | Use the **anon** key with RLS on, not the service-role key |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | ✅ | — | Comma-separated frontend origins. **No default on purpose** — the app refuses to start if unset, so a misconfigured deploy can never silently allow all origins with credentials |
| `SERVER_PORT` | | `8080` | |
| `AUTH_COOKIE_SECURE` | | `true` | `false` only for local http |
| `AUTH_COOKIE_SAME_SITE` | | `Lax` | Must be `None` for the split S3→EC2 deploy (and `None` requires `SECURE=true`) |
| `AUTH_SESSION_HOURS` | | `8` | Session lifetime |

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
`apikey` + `Authorization: Bearer` headers and hits `${supabase.url}/rest/v1/<endpoint>`.

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

### `AuthInterceptor` — the whole authorization policy

Registered in `CorsConfig` against `/api/**`. It runs before every controller and does three things:

1. **Lets some requests straight through** (`isPublicEndpoint`):
   - `/api/login`, `/api/logout`, `/api/session`, `/api/health`
   - **any `GET`** on `/api/events`, `/api/event-types`, `/api/roles`
   - `POST /api/attendees` (public check-in — anyone attending an event can sign in without an account)
   - all `OPTIONS` requests (CORS preflight)
2. Otherwise resolves the session cookie to a user; **401** if there isn't one. The user is stashed
   as the request attribute `currentUser`, which is how controllers get the caller.
3. Applies the admin rule (`requiresAdmin`); **403** if it fails:
   - everything under `/api/users` is admin-only, **including GET**
   - non-GET on `/api/roles` and `/api/event-types` is admin-only

Everything else (creating/editing/deleting your own reservations and drafts) is allowed for any
logged-in user — the *ownership* check happens inside the controller, not here.

> **Read this twice:** `GET /api/events` is public and returns **every** event, private ones
> included, with host names attached. `/api/events/public` exists and filters to
> `is_public=true`, but nothing currently calls it. If private reservations are meant to be
> private from anonymous visitors, this is the line to change
> (`AuthInterceptor.isPublicEndpoint`) — see §8.

### `AuthService` and `SessionService` — login

`AuthService`:
- `authenticate(email, password)` — looks the user up, verifies with BCrypt, returns a **sanitized**
  copy (`password_hash` removed).
- Password check **fails closed**: if the stored value isn't a `$2a$`/`$2b$`/`$2y$` BCrypt hash it is
  never a match, so a legacy plaintext password can't be used to log in.
- `isAdmin(user)` — role name equals `"admin"`, case-insensitive. Role can arrive as `role_name`,
  `role`, or nested `user_roles.name`; `getRoleName` tries all three.

`SessionService`:
- On login, generates 32 random bytes from `SecureRandom`, base64url-encodes them, inserts a row in
  `sessions`, and returns an `HttpOnly` cookie.
- On every authenticated request, `getCurrentUser` reads the cookie, loads the session row, rejects
  it if `invalidated_at` is set or `expires_at` has passed (invalidating it on the way out), then
  updates `last_seen_at` and returns the user.
- Logout sets `invalidated_at` and returns a cookie with `maxAge=0`.

Cookie flags come from config: `HttpOnly` always, `Secure` and `SameSite` from env vars.

**Note:** sessions are never bulk-purged. The `sessions` table grows forever unless someone adds a
cleanup job.

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

**Ownership**: `canSaveWithRequestedHost` (for writes) and `canModifyExistingEvent` (for
edit/delete) both say: admins may do anything; everyone else only for `host_user_id == their own id`.
This is what stops a logged-in user from booking rooms in someone else's name.

Endpoints:

| Method | Path | Notes |
|---|---|---|
| GET | `/api/events` | all events + host first/last name |
| GET | `/api/events/{id}` | |
| GET | `/api/events/by-user/{userId}` | |
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
- Deleting is a **soft** delete — it stamps `discarded_at` rather than removing the row, and the
  filter `discarded_at=is.null` hides it everywhere. (Consequence: this one DELETE doesn't use
  `DeleteOutcome`, and it returns 204 without checking the PATCH result.)

### `UserController` — `/api/users` (admin only)

- `buildUserPayload` whitelists `email`, `first_name`, `last_name`, `phone`, `role_name`, `enabled`.
- **A client can never set `password_hash` directly.** Only a `password` field is accepted, and it
  is BCrypt-hashed here on the server.
- On create, email and password are required. On update, a blank password means "keep the current
  one" (the frontend simply omits the field).
- `sanitizeUserResponse` walks the whole response tree and strips `password_hash` before returning,
  belt-and-braces with `AuthService`.
- There is **no hard delete of users from the app** — the UI disables accounts by setting
  `enabled=false`. A `DELETE /api/users/{id}` exists and will return 409 if the account still has
  reservations or sessions.

### `AttendeeController` — `/api/attendees`

Check-ins. `POST` is **public and unauthenticated** — that is the point, attendees don't have
accounts. Because raw client JSON must never reach PostgREST on a public endpoint,
`buildAttendeePayload` whitelists `event_id`, `sdccd_id`, `first_name`, `last_name`, `email`, and
lets `check_in_time` fall back to the table default `now()`. `event_id` and `first_name` are
required on create. All the `GET`s require a login.

### `EventTypeController` / `UserRoleController`

The thinnest controllers — near pass-throughs. GET is public, writes are admin-only (enforced by
`AuthInterceptor`, not by code in the controller). **These two do *not* whitelist their request
bodies**; whatever JSON you POST goes to PostgREST as-is. That's tolerable only because they're
admin-only. See §8.

### `GlobalExceptionHandler`

- `IllegalArgumentException` → **400** with the message (that's how validation failures surface).
- Anything else → **500 `{"error":"Internal server error"}`**, deliberately generic. Never add
  `e.getMessage()` here; it would leak Supabase hosts and transport errors to the browser.

---

## 5. Endpoint map

> Quick orientation only. For request bodies, response shapes, every error message, curl examples
> and per-endpoint caveats, see **[`API.md`](API.md)**.

Auth column: **public** = no login needed; **login** = any authenticated user; **admin** = admin role
required. Enforced in `AuthInterceptor`; ownership checks marked ★ happen inside the controller.

| Method | Path | Auth | Notes |
|---|---|:--:|---|
| GET | `/api/health` | public | liveness probe |
| POST | `/api/login` | public | sets the `session_id` cookie |
| POST | `/api/logout` | public | invalidates the session, clears the cookie |
| GET | `/api/session` | public | 401 when there is no valid session |
| GET | `/api/events` | public | **returns private events too** |
| GET | `/api/events/{id}` | public | |
| GET | `/api/events/by-user/{userId}` | public | |
| GET | `/api/events/public` | public | `is_public=true` only; currently unused |
| POST | `/api/events` | login ★ | |
| POST | `/api/events/batch` | login ★ | array body |
| POST | `/api/events/recurring` | login ★ | template + week ranges |
| PATCH | `/api/events/{id}` | login ★ | |
| DELETE | `/api/events/{id}` | login ★ | deletes the event's attendees first |
| GET | `/api/reservation-drafts` | login | always scoped to the caller |
| POST | `/api/reservation-drafts` | login ★ | upsert |
| DELETE | `/api/reservation-drafts/{id}` | login | soft delete (`discarded_at`) |
| GET | `/api/attendees` | login | |
| GET | `/api/attendees/{id}` | login | |
| GET | `/api/attendees/by-event/{eventId}` | login | |
| POST | `/api/attendees` | **public** | the public check-in |
| PATCH | `/api/attendees/{id}` | login | |
| DELETE | `/api/attendees/{id}` | login | |
| GET | `/api/event-types` | public | |
| GET | `/api/event-types/{eventType}` | public | |
| POST / PATCH / DELETE | `/api/event-types…` | admin | body not whitelisted |
| GET | `/api/roles` | public | |
| GET / PATCH / DELETE | `/api/roles/{id}` | public / admin | **broken — see §8.2** |
| POST | `/api/roles` | admin | body not whitelisted |
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
| `sessions` | `session_id` (unique token), `user_id`, `expires_at`, `invalidated_at`, `last_seen_at` |
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

## 8. Known gaps — please read before you change anything

Things a new maintainer will otherwise discover the hard way. None of them is currently breaking
the app; all are worth a decision.

1. **`GET /api/events` is public and returns private events.** `is_public` is respected by the UI
   but not by the API. `/api/events/public` exists but is unused. Anyone can `curl` the full
   calendar with host names.

   Three more rules likewise exist **only in the browser**: `users.enabled` is never read by the
   backend (a disabled user can still log in and book), there is no overlap check so the API will
   happily double-book the room, and the 8 AM start of day is not enforced. See
   [`API.md`](API.md) gotchas 2, 3 and 10.
2. **`/api/roles/{id}` is broken.** `UserRoleController` queries `user_roles?id=eq.{id}`, but
   `user_roles` has no `id` column — its primary key is `name`. GET-by-id, PATCH, and DELETE on
   roles will not work as written. Nothing in the frontend calls them, which is why nobody noticed.
3. **`EventTypeController` and `UserRoleController` forward raw request bodies** to PostgREST with
   no column whitelist, unlike every other controller. Admin-only, so not urgent, but inconsistent.
4. **Sessions are never cleaned up.** Expired and invalidated rows accumulate in `sessions` forever.
5. **No automated tests at all.** `mvn package` compiles; it verifies nothing. There is no test
   directory.
6. **No CI.** Deploys are manual (`git pull && mvn clean package && java -jar target/*.jar`, or
   Docker). The root `README.md` has the commands.
7. **`README.md` references `testData.sql`, which is not in the repo.** Only `erd.sql` exists — you
   will need to write your own seed data (and at least one user row with a BCrypt `password_hash`,
   or you cannot log in).
8. **Batch inserts are not transactional across validation.** `POST /api/events/batch` validates
   everything before inserting, so it's fine; but the frontend's *fallback* path creates events one
   at a time and tries to roll back by deleting — see the frontend doc §4.

---

## 9. Glossary

- **PostgREST** — the service that turns Supabase tables into a REST API. `?id=eq.5` means
  `WHERE id = 5`; `?select=*,users(first_name)` is a join.
- **`JsonNode` / `ObjectNode`** — Jackson's untyped JSON tree. This codebase uses it instead of DTOs.
- **`@Component` / `@Service` / `@RestController`** — "Spring, create one of these at startup and
  inject it where needed." Constructor injection is used throughout.
- **Interceptor** — Spring MVC's before-the-controller hook. `AuthInterceptor` is the gate.
- **SQLSTATE 23503** — the Postgres error code for a foreign-key violation.
