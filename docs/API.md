# API Reference

Endpoint-by-endpoint reference for the backend. For *how the backend is built* (architecture,
classes, how to run it), see [`BACKEND.md`](BACKEND.md).

Base URL: `<backend origin>/api` — e.g. `http://localhost:8080/api`.

---

## Contents

- [Conventions](#conventions) — auth, error shape, response shapes, status-code caveats
- [Quick reference](#quick-reference) — every endpoint on one screen
- [Auth](#auth) — `/login`, `/logout`, `/session`
- [Health](#health) — `/health`
- [Events](#events) — `/events`, `/events/batch`, `/events/recurring`
- [Reservation drafts](#reservation-drafts) — `/reservation-drafts`
- [Attendees](#attendees) — `/attendees`
- [Event types](#event-types) — `/event-types`
- [Users](#users) — `/users`
- [Roles](#roles) — `/roles`
- [Cross-cutting gotchas](#cross-cutting-gotchas)

---

## Conventions

### Authentication

Cookie-based. `POST /api/login` returns a `Set-Cookie: session_id=…` header; every subsequent
request must send it back. The cookie is `HttpOnly` (JavaScript cannot read it), and `Secure` /
`SameSite` come from the backend's env vars. The database stores a SHA-256 hash of the cookie token,
not the token itself.

In the browser this means **every** request needs `credentials: "include"` — `js/api.js` does this
in one place. With curl, use a cookie jar:

```bash
# log in and save the cookie
curl -c jar -X POST http://localhost:8080/api/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"secret"}'

# reuse it
curl -b jar http://localhost:8080/api/events
```

There are three access levels, enforced by `AuthInterceptor` before any controller runs:

| Level | Meaning |
|---|---|
| **public** | No cookie needed |
| **login** | Any valid session |
| **faculty/admin** | Session whose role is `faculty` or `admin` |
| **admin** | Session whose role is `admin` (case-insensitive) |

Some endpoints add an **ownership** check inside the controller on top of `login`: you may only
touch rows whose `host_user_id` / `user_id` is yours. Admins bypass ownership checks.

### Content type

Send `Content-Type: application/json` on every request with a body. All responses are JSON.

### Error shape

Every error the backend generates itself looks like this:

```json
{ "error": "Human-readable message" }
```

| Status | When |
|---|---|
| `400` | Validation failed. The message names the problem field. |
| `401` | No session, expired session, or invalidated session. |
| `403` | Logged in, but not allowed (not an admin / not the owner). |
| `404` | The row does not exist (only on `/events/{id}` PATCH and DELETE). |
| `409` | A delete was refused because other rows still reference this one. |
| `502` | The database refused or failed the write for another reason. |
| `500` | `{"error":"Internal server error"}` — deliberately generic. |

**A `500` never contains detail.** That is intentional (see `GlobalExceptionHandler`), so Supabase
hostnames and stack traces can't leak. When you get a 500, the real cause is in the **server log**.

### Response shapes

Most reads return a JSON array, even when you fetch by id. A missing row is `[]`, not a 404. Private
events are filtered on the server for callers without a faculty or admin session. The public
calendar receives the event ID, start/end time, `is_public: false`, and generic title only. The
schedule interval remains visible so it can still mark the room as occupied.

```bash
GET /api/events/999   →   200 []
```

Writes send `Prefer: return=representation` to PostgREST, so a successful `POST`/`PATCH` returns
**an array containing the row(s) written**, not a bare object. The one exception is
`POST /api/login`, which returns a single user object.

`?select=…` joins mean some responses carry a nested object. Public event data for private events
does not include the host join.

### Status codes and error bodies

The API does not return PostgREST error bodies. Database failures are mapped to generic server
errors so constraint details and database messages are not exposed to clients.

| Endpoint group | Behaviour |
|---|---|
| `POST`/`PATCH` on `/events`, `/events/batch`, `/events/recurring`, `/reservation-drafts`, `/users` | Input errors return 400; database errors use a generic 502 response. |
| All `DELETE` | **Inspected properly** — 204 / 409 / 502 (see `DeleteOutcome`). Draft deletion also verifies the row is absent. |
| Every `GET` | Successful reads return 200; database failures return a generic 500. |
| `POST` on `/attendees`, `/event-types`, `/roles` | Successful writes return 201; database rejections return a generic 502. |
| `PATCH` on `/attendees`, `/event-types`, `/roles` | Successful writes return 200; database rejections return a generic 502. |

Database error bodies are not returned to clients. Error responses contain a generic message so
table names, constraint details, and database configuration are not exposed.

---

## Quick reference

★ = ownership checked inside the controller.

| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| `GET` | `/api/health` | public | Liveness probe |
| `POST` | `/api/login` | public | Log in, set session cookie |
| `POST` | `/api/logout` | public | Invalidate session, clear cookie |
| `GET` | `/api/session` | public | Who am I |
| `GET` | `/api/events` | public | Public event details and masked private event schedule blocks |
| `GET` | `/api/events/{id}` | public | One event |
| `GET` | `/api/events/by-user/{userId}` | public | Events hosted by a user |
| `GET` | `/api/events/public` | public | Only `is_public=true` |
| `POST` | `/api/events` | login ★ | Create one |
| `POST` | `/api/events/batch` | login ★ | Create many |
| `POST` | `/api/events/recurring` | login ★ | Create a weekly series compactly |
| `PATCH` | `/api/events/{id}` | faculty owner/admin ★ | Update one, including its event type |
| `DELETE` | `/api/events/{id}` | faculty owner/admin ★ | Delete one (+ its check-ins) |
| `GET` | `/api/reservation-drafts` | login | Your drafts |
| `POST` | `/api/reservation-drafts` | login ★ | Save/update a draft |
| `DELETE` | `/api/reservation-drafts/{id}` | login | Discard a draft |
| `GET` | `/api/attendees` | faculty/admin | All check-ins |
| `GET` | `/api/attendees/{id}` | faculty/admin | One check-in |
| `GET` | `/api/attendees/by-event/{eventId}` | faculty/admin | Check-ins for an event |
| `POST` | `/api/attendees` | **public** | Check in to an event |
| `PATCH` | `/api/attendees/{id}` | faculty/admin | Update a check-in |
| `DELETE` | `/api/attendees/{id}` | faculty/admin | Delete a check-in |
| `GET` | `/api/event-types` | public | All event types |
| `GET` | `/api/event-types/{eventType}` | public | One event type |
| `POST` | `/api/event-types` | admin | Create |
| `PATCH` | `/api/event-types/{eventType}` | admin | Update |
| `DELETE` | `/api/event-types/{eventType}` | admin | Delete |
| `GET` | `/api/users` | admin | All users |
| `GET` | `/api/users/{id}` | admin | One user |
| `POST` | `/api/users` | admin | Create user |
| `PATCH` | `/api/users/{id}` | admin | Update user / disable / enable |
| `DELETE` | `/api/users/{id}` | admin | Delete user |
| `GET` | `/api/roles` | public | All roles |
| `GET` | `/api/roles/{name}` | public | Find a role by primary key |
| `POST` | `/api/roles` | admin | Create role |
| `PATCH` | `/api/roles/{name}` | admin | Update a role |
| `DELETE` | `/api/roles/{name}` | admin | Delete a role |

---

## Auth

### `POST /api/login`

**Auth:** public

**Request**

```json
{ "email": "ada@sdmesa.edu", "password": "secret" }
```

Both fields are required. Email is trimmed and lowercased before lookup. Passwords over 72 UTF-8
bytes are rejected to match BCrypt's input limit. Disabled users cannot log in.

**Success — `200`**

Sets `Set-Cookie: session_id=…; HttpOnly; Path=/; Max-Age=<AUTH_SESSION_HOURS>`, and returns the
user **as a single object** (the only endpoint that does):

```json
{
  "id": 3,
  "email": "ada@sdmesa.edu",
  "first_name": "Ada",
  "last_name": "Lovelace",
  "phone": "619-555-0100",
  "role_name": "faculty",
  "enabled": true,
  "user_roles": { "name": "faculty" }
}
```

`password_hash` is always stripped.

**Errors**

| Status | Body | Cause |
|---|---|---|
| `401` | `{"error":"Invalid credentials"}` | Unknown email, or wrong password |
| `500` | `{"error":"Login failed"}` | Database unreachable |

**Notes**

- The stored password must be a BCrypt hash (`$2a$` / `$2b$` / `$2y$`). Anything else **never**
  matches — a plaintext password in the database cannot be used to log in.
- The same `401` is returned for "no such user" and "wrong password", so the endpoint doesn't reveal
  which emails exist.
- Disabled accounts cannot log in. Existing sessions are rejected after the account is disabled.

```bash
curl -i -c jar -X POST http://localhost:8080/api/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@sdmesa.edu","password":"secret"}'
```

---

### `GET /api/session`

**Auth:** public (but answers `401` without a valid session)

Returns the logged-in user, in the same shape as `POST /api/login`. Use it to check whether a
session is still alive — the frontend calls it on every page load.

**Errors**

| Status | Body |
|---|---|
| `401` | `{"error":"No active session"}` |
| `500` | `{"error":"Session lookup failed"}` |

**Notes**

- A session is rejected if the cookie is missing, the row is gone, `invalidated_at` is set, or
  `expires_at` has passed. Expired/invalid sessions are invalidated as a side effect of the check.
- A successful call updates `last_seen_at` on the session row.

```bash
curl -b jar http://localhost:8080/api/session
```

---

### `POST /api/logout`

**Auth:** public

No request body. Stamps `invalidated_at` on the session row and returns a cookie with `Max-Age=0`
to clear it in the browser.

**Success — `200`** `{"success":true}`

**Notes** — always returns `200`, even with no cookie or an already-dead session. There is no error
case; logging out twice is harmless.

---

## Health

### `GET /api/health`

**Auth:** public. **Success — `200`** `{"status":"ok"}`

For load balancers and uptime checks. Touches nothing — it will answer `200` even when Supabase is
completely unreachable, so **it is a liveness probe, not a readiness probe.**

---

## Events

An event (a.k.a. reservation) is one booking of the room.

**Event object**

```json
{
  "id": 12,
  "host_user_id": 3,
  "start_time": "2026-03-04T17:00:00+00:00",
  "end_time": "2026-03-04T18:00:00+00:00",
  "event_type": "Meeting",
  "description": "Weekly sync",
  "title": "CS Club",
  "department": "Computer Science",
  "is_public": true,
  "recurrence_group_id": null,
  "users": { "first_name": "Ada", "last_name": "Lovelace" }
}
```

`users` is a read-only join, present on `GET` only. `recurrence_group_id` is a free-text id (the
browser generates a UUID) shared by every occurrence of a repeating booking — that is how
"delete the whole series" finds its members.

### Writable fields

These are the **only** fields accepted on write. Anything else you send is silently dropped —
including `id`, which is why the frontend can safely send a whole event object back on update.

| Field | Type | Required | Aliases accepted |
|---|---|:--:|---|
| `host_user_id` | integer | ✅ | `user_id` |
| `start_time` | timestamp | ✅ | `start` |
| `end_time` | timestamp | ✅ | `end` |
| `event_type` | string | ✅ | — |
| `description` | string | ✅ | — |
| `title` | string | ✅ | — |
| `department` | string | ✅ | — |
| `is_public` | boolean | ✅ | — |
| `recurrence_group_id` | string | | — |

Integers may be sent as numeric strings (`"3"`); booleans may be sent as `"true"`/`"false"`.
Timestamps are accepted as ISO instant (`2026-03-04T17:00:00Z`), offset date-time
(`2026-03-04T09:00:00-08:00`), or bare local date-time (`2026-03-04T09:00:00`).

### Validation rules

Applied to every create and update, and to every generated occurrence of a recurring request:

1. All eight required fields present and non-blank.
2. `end_time` strictly after `start_time`.
3. `start_time` **strictly before 5:00 PM**, evaluated in **`America/Los_Angeles`**.
4. `end_time` **not after 5:00 PM**, same zone — ending exactly at 5:00 PM is allowed.

Any failure is a `400` naming the field, e.g. `{"error":"Reservations must end by 5:00 PM."}`.

Two things this does **not** do:

> ⚠️ **There is no earliest-time rule.** The calendar UI starts at 8 AM, but that is a frontend
> constant (`CALENDAR_START_HOUR`); the API happily accepts a 6 AM booking. Only the 5 PM end is
> enforced server-side.

> ⚠️ **There is no overlap check on the server.** Two reservations may occupy the same time. Overlap
> is only prevented by the browser (`utils/reservationValidation.js`). See
> [gotcha 3](#3-double-booking-is-only-prevented-in-the-browser).

Because the window is evaluated in Pacific time, the same UTC timestamp can be valid in winter and
invalid in summer. Send zoned timestamps and let the server convert.

---

### `GET /api/events`

**Auth:** public. Returns an array of every event, each with its host's name.

⚠️ This includes events with `is_public: false`. See
[gotcha 1](#1-private-events-are-readable-by-anyone).

```bash
curl http://localhost:8080/api/events
```

### `GET /api/events/{id}`

**Auth:** public. Array with 0 or 1 element — a missing event is `200 []`, not `404`.

### `GET /api/events/by-user/{userId}`

**Auth:** public. Every event where `host_user_id = {userId}`.

### `GET /api/events/public`

**Auth:** public. Only `is_public = true`. Currently unused by the frontend; it is the endpoint an
anonymous calendar *should* be calling.

---

### `POST /api/events`

**Auth:** login. You may only create events with `host_user_id` equal to your own id — unless you
are an admin, who may create for anyone.

**Request** — see [Writable fields](#writable-fields).

```json
{
  "host_user_id": 3,
  "start_time": "2026-03-04T17:00:00Z",
  "end_time": "2026-03-04T18:00:00Z",
  "event_type": "Meeting",
  "title": "CS Club",
  "description": "Weekly sync",
  "department": "Computer Science",
  "is_public": true
}
```

**Success — `201`**: an array containing the created row, including its new `id`. (The status comes
straight from PostgREST, so treat `2xx` rather than exactly `201` as success.)

**Errors**

| Status | Body | Cause |
|---|---|---|
| `400` | `{"error":"title is required."}` etc. | Validation |
| `403` | `{"error":"You do not have permission to save this reservation for that host."}` | `host_user_id` isn't yours |
| 4xx/5xx | PostgREST body | Passed through — e.g. `event_type` not in `event_types` |

```bash
curl -b jar -X POST http://localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -d '{"host_user_id":3,"start_time":"2026-03-04T17:00:00Z","end_time":"2026-03-04T18:00:00Z","event_type":"Meeting","title":"CS Club","description":"Weekly sync","department":"Computer Science","is_public":true}'
```

---

### `POST /api/events/batch`

**Auth:** login ★ (every element is ownership-checked)

Creates many events in **one** database insert. Accepts either a bare array or `{"events": [ … ]}`.

```json
[ { …event… }, { …event… } ]
```

**Success** — array of all created rows, in request order.

**Errors** — **all or nothing**: every element is validated *before* anything is inserted, so a
single bad element aborts the whole request and nothing is written. Messages are prefixed with the
1-based position:

| Status | Body |
|---|---|
| `400` | `{"error":"At least one reservation is required."}` |
| `400` | `{"error":"Reservation 3: End time must be after start time."}` |
| `403` | `{"error":"You do not have permission to save reservation 2 for that host."}` |

---

### `POST /api/events/recurring`

**Auth:** login ★

Creates a weekly repeating booking without sending one object per week. You send a template plus a
description of the repeats; the server expands it and inserts everything in one call.

**Request**

```json
{
  "event": {
    "host_user_id": 3,
    "event_type": "Meeting",
    "title": "Office Hours",
    "description": "Drop-in",
    "department": "Computer Science",
    "is_public": true,
    "recurrence_group_id": "b0b7…-uuid"
  },
  "ranges": [
    {
      "start_time": "2026-03-04T17:00:00Z",
      "end_time":   "2026-03-04T18:00:00Z",
      "week_count": 16,
      "excluded_week_offsets": [5, 11]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `event` | The event template. **Must not contain `start_time`/`end_time`** — those come from the ranges and are overwritten anyway. All other required fields apply. |
| `ranges[].start_time` / `end_time` | Week 0 of this range. |
| `ranges[].week_count` | Positive whole number. Total weeks including week 0. |
| `ranges[].excluded_week_offsets` | Optional array of 0-based week numbers to skip (e.g. spring break). Each must be `0 ≤ n < week_count`. |

Each range produces `week_count − excluded.length` events, each 7 days after the previous, validated
individually. Multiple ranges let one request cover, say, both a Tuesday and a Thursday slot.

Set the same `recurrence_group_id` on the template for every occurrence to be deletable as a series.

**Success** — array of all created rows.

**Errors**

| Status | Body |
|---|---|
| `400` | `{"error":"Recurring reservation body must be a JSON object."}` |
| `400` | `{"error":"Recurring reservation event details are required."}` |
| `400` | `{"error":"At least one recurring reservation time range is required."}` |
| `400` | `{"error":"Recurring reservation week_count must be a positive whole number."}` |
| `400` | `{"error":"Recurring reservation excluded week offsets must fall within the requested duration."}` |
| `400` | `{"error":"A recurring reservation cannot exclude every week."}` |
| `400` | `{"error":"Recurring reservation range 1, week 7: Reservations must end by 5:00 PM."}` |
| `403` | `{"error":"You do not have permission to save this recurring reservation for that host."}` |

Note the last `400` example: validation errors name **both** the range and the week, so the user
can be told exactly which occurrence is the problem.

**Note on daylight saving:** occurrences are generated with `plusWeeks()` on a zoned date-time in
`America/Los_Angeles`, so a series spanning a DST change keeps its **wall-clock** time, which is
what people expect from a recurring meeting. Verified: a series starting 9:00 AM PST on 2026-03-04
produces `17:00Z` for week 0 and `16:00Z` for week 2 — both 9:00 AM local, across the March 8
transition.

---

### `PATCH /api/events/{id}`

**Auth:** faculty owner or admin. A faculty user can update only an event whose stored host ID
matches their user ID. Admins can update any event.

**Request** — same fields as create.

> ⚠️ **This is not a partial update.** The body goes through the same normalize-and-validate step as
> a create, so **all eight required fields must be present** or you get a `400`. Send the whole
> event, not just the changed field. Any field you omit is also cleared from the update payload, so
> a partial body would blank nothing but fail validation first.

**Success** — array containing the updated row.

**Errors**

| Status | Body |
|---|---|
| `404` | `{"error":"Reservation not found."}` |
| `403` | `{"error":"You do not have permission to modify this reservation."}` |
| `403` | `{"error":"You do not have permission to save this reservation for that host."}` (you tried to reassign the host to someone else) |
| `400` | Validation message |

The stored event ownership and the submitted `host_user_id` are both checked. Faculty can update
their own event, including its event type, but cannot take over another faculty member's event or
reassign their event to another host. Admins can update any event. The separate event-type catalog
remains admin-managed because those shared records are not owned by an individual event.

**Order of checks:** existence (`404`) → ownership (`403`) → body validation (`400`). So a partial
body sent to someone else's event returns `403`, not `400` — the error you get tells you about the
first failing check only.

---

### `DELETE /api/events/{id}`

**Auth:** login — host or admin.

**Success — `204 No Content`**, empty body.

**What it does:** `attendees.event_id` has no `ON DELETE` rule, so Postgres will refuse to delete an
event that has check-ins. This endpoint therefore **deletes the event's attendee rows first** —
deleting a reservation is taken to discard its check-in records with it — and only then deletes the
event. If the attendee cleanup fails, the reservation is left untouched.

**Errors**

| Status | Body |
|---|---|
| `404` | `{"error":"Reservation not found."}` |
| `403` | `{"error":"You do not have permission to delete this reservation."}` |
| `502` | `{"error":"The check-in records for this reservation could not be removed, so the reservation was left in place."}` |
| `409` | `{"error":"This reservation is still referenced by other records, so it cannot be deleted."}` |
| `502` | `{"error":"The delete could not be completed. Please try again."}` |

**There is no "delete series" endpoint.** The frontend deletes a recurring series by looking up
every event sharing the `recurrence_group_id` and issuing one `DELETE` each, sequentially. That is
not atomic — a failure part-way leaves the earlier ones deleted.

---

## Reservation drafts

Server-side autosave for half-finished reservation forms, so a user who closes a modal (or switches
device) doesn't lose their work. The `payload` is opaque to the backend — it stores whatever JSON
the form gives it.

**Draft object**

```json
{
  "id": 7,
  "user_id": 3,
  "host_user_id": 3,
  "source_event_id": 12,
  "draft_type": "edit",
  "payload": { "fields": { … }, "time_ranges": [ … ] },
  "created_at": "2026-03-01T10:00:00+00:00",
  "updated_at": "2026-03-01T10:04:12+00:00",
  "discarded_at": null
}
```

`draft_type` is `"create"` (a new reservation) or `"edit"` (changes to an existing one, identified
by `source_event_id`).

**Every operation is scoped to the caller.** You cannot read, write, or discard another user's
draft — not even as an admin.

---

### `GET /api/reservation-drafts`

**Auth:** login

**Query parameters** (both optional)

| Param | Effect |
|---|---|
| `draft_type` | `create` or `edit`. Anything else → `400`. Passing `create` also forces `source_event_id IS NULL`. |
| `source_event_id` | Restrict to drafts for that event. |

Always filtered to your own drafts and to `discarded_at IS NULL`, ordered by `updated_at` descending.

**Success — `200`** array of drafts (`[]` if none). The frontend just takes element `[0]`.

```bash
curl -b jar 'http://localhost:8080/api/reservation-drafts?draft_type=edit&source_event_id=12'
```

---

### `POST /api/reservation-drafts`

**Auth:** login. For `edit` drafts you must own the source event (or be admin).

**Request**

```json
{
  "draft_type": "edit",
  "source_event_id": 12,
  "host_user_id": 3,
  "payload": { "anything": "you like" }
}
```

| Field | Required | Notes |
|---|:--:|---|
| `draft_type` | ✅ | `create` or `edit`, trimmed and lowercased |
| `payload` | ✅ | Must be a JSON **object** |
| `source_event_id` | for `edit` | Ignored (stored as null) for `create` drafts |
| `host_user_id` | | Optional bookkeeping |

`user_id` is always taken from your session — sending one is pointless.

**This is an upsert.** If you already have a non-discarded draft with the same
(user, `draft_type`, `source_event_id`), it is updated; otherwise a new one is created. A partial
unique index in the schema enforces one active draft per combination, so you never accumulate
duplicates.

**Success** — array containing the saved draft. Status is propagated from the database.

**Errors**

| Status | Body |
|---|---|
| `400` | `{"error":"draft_type must be create or edit."}` |
| `400` | `{"error":"source_event_id is required for edit drafts."}` |
| `400` | `{"error":"payload must be a JSON object."}` |
| `400` | `{"error":"Draft body could not be parsed."}` / `"Draft body must be a JSON object."` |
| `403` | `{"error":"You do not have permission to save a draft for this reservation."}` |

---

### `DELETE /api/reservation-drafts/{id}`

**Auth:** login

**Success — `204 No Content`**

A permanent delete scoped to `id` **and** your `user_id`, so you cannot delete someone else's
draft. The endpoint checks the database response and confirms the row is absent before returning
`204`. A failed deletion returns an error instead of a false success. Older soft-discarded rows
remain in the database until a one-time cleanup is run.

---

## Attendees

A check-in: one person recording that they attended an event. Attendees do **not** have accounts.

**Attendee object**

```json
{
  "id": 41,
  "event_id": 12,
  "sdccd_id": 1234567,
  "first_name": "Grace",
  "last_name": "Hopper",
  "email": "grace@student.sdccd.edu",
  "check_in_time": "2026-03-04T17:03:11+00:00",
  "events": { "title": "CS Club" }
}
```

### `GET /api/attendees`

**Auth:** faculty/admin. Every check-in in the system, each with its event's title.

### `GET /api/attendees/{id}`

**Auth:** faculty/admin. Array with 0 or 1 element.

### `GET /api/attendees/by-event/{eventId}`

**Auth:** faculty/admin. All check-ins for one event, which is what the attendee list in the reservation
modal uses.

---

### `POST /api/attendees`

**Auth:** 🔓 **public** — the only unauthenticated write in the API. That is deliberate: people
attending an event sign themselves in from the public event modal, without an account.

**Request**

| Field | Type | Required |
|---|---|:--:|
| `event_id` | integer | ✅ |
| `first_name` | string | ✅ |
| `last_name` | string | |
| `sdccd_id` | integer | |
| `email` | string | |

```json
{ "event_id": 12, "first_name": "Grace", "last_name": "Hopper",
  "sdccd_id": 1234567, "email": "grace@student.sdccd.edu" }
```

Because this endpoint is public, the body is **strictly whitelisted** — those five columns and
nothing else reach the database. `check_in_time` is set by the table default (`now()`) and cannot be
supplied.

**Success: `201`** with the created row.

**Errors**

| Status | Body |
|---|---|
| `400` | `{"error":"event_id is required"}` |
| `400` | `{"error":"first_name is required"}` |
| `400` | `{"error":"Malformed request body"}` |

Database rejections return a generic `502` error. PostgREST error details are not sent to the
caller.

```bash
curl -X POST http://localhost:8080/api/attendees \
  -H 'Content-Type: application/json' \
  -d '{"event_id":12,"first_name":"Grace","last_name":"Hopper"}'
```

**Note:** there is no rate limiting and no duplicate check. The same person can check in repeatedly,
and anyone who knows an event id can post check-ins to it.

---

### `PATCH /api/attendees/{id}`

**Auth:** faculty/admin. Same whitelist as create; nothing is required, but the payload can't be empty
(`400 {"error":"No updatable fields provided"}`). Returns `200` with the updated row.

### `DELETE /api/attendees/{id}`

**Auth:** faculty/admin. `204` on success.

| Status | Body |
|---|---|
| `409` | `{"error":"This check-in is still referenced elsewhere, so it cannot be deleted."}` |
| `502` | `{"error":"The delete could not be completed. Please try again."}` |

---

## Event types

The lookup table behind the "Event type" dropdown. The primary key **is the string itself** —
there is no numeric id.

**Event type object** — `{ "event_type": "Study_Group", "description": "Student study session" }`

`events.event_type` is a foreign key to this table, so a value that isn't listed here cannot be
saved on an event.

### `GET /api/event-types`

**Auth:** public. Array of all types.

### `GET /api/event-types/{eventType}`

**Auth:** public. Array with 0 or 1 element. `{eventType}` is the string key, e.g.
`/api/event-types/Study_Group`.

### `POST /api/event-types` · `PATCH /api/event-types/{eventType}`

**Auth:** admin

The body is whitelisted to `event_type` and `description`:

```json
{ "event_type": "Lecture", "description": "Scheduled class" }
```

Successful creates return `201`; successful updates return `200`. Database rejections return a
generic `502`.

### `DELETE /api/event-types/{eventType}`

**Auth:** admin. `204` on success.

| Status | Body |
|---|---|
| `409` | `{"error":"Reservations are still using this event type, so it cannot be deleted."}` |
| `502` | `{"error":"The delete could not be completed. Please try again."}` |

The `409` is the common case: you cannot remove a type while any event references it. Repoint or
delete those events first.

The route value is URL-encoded before it is sent to PostgREST.

---

## Users

**Every endpoint here is admin-only, including the `GET`s.**

**User object**

```json
{
  "id": 3, "email": "ada@sdmesa.edu",
  "first_name": "Ada", "last_name": "Lovelace",
  "phone": "619-555-0100", "role_name": "faculty", "enabled": true,
  "user_roles": { "name": "faculty" }
}
```

`password_hash` is stripped from every response, recursively, before it leaves the backend.

### Writable fields

| Field | Notes |
|---|---|
| `email` | Required on create. Unique in the database. |
| `password` | **Plaintext, hashed server-side with BCrypt.** Required on create. Omit or leave blank on update to keep the existing password. |
| `first_name`, `last_name`, `phone` | |
| `role_name` | Foreign key to `user_roles.name` — e.g. `admin`, `faculty` |
| `enabled` | boolean |

🔒 **`password_hash` can never be set by a client.** It is not in the whitelist; only `password` is
accepted, and it is hashed here. Anything outside this list is silently dropped (including `id`,
which is why the frontend can safely PATCH a whole user object back).

### `GET /api/users` · `GET /api/users/{id}`

**Auth:** admin. Arrays of user objects.

### `POST /api/users`

**Auth:** admin

```json
{ "email": "grace@sdmesa.edu", "password": "initial-secret",
  "first_name": "Grace", "last_name": "Hopper", "role_name": "faculty", "enabled": true }
```

**Errors**

| Status | Body |
|---|---|
| `400` | `{"error":"email is required"}` |
| `400` | `{"error":"password is required"}` |
| `400` | `{"error":"Malformed request body"}` |

Duplicate email or other database rejections return a generic `502`. The database error body is not
sent to the caller.

### `PATCH /api/users/{id}`

**Auth:** admin. Partial update — send only what changes. `400 {"error":"No updatable fields
provided"}` if nothing recognisable is sent.

Disabling and enabling an account is just this endpoint:

```bash
curl -b jar -X PATCH http://localhost:8080/api/users/5 \
  -H 'Content-Type: application/json' -d '{"enabled":false}'
```

Setting `enabled: false` blocks new logins and invalidates the user's existing session on its next
request.

### `DELETE /api/users/{id}`

**Auth:** admin. `204` on success.

| Status | Body |
|---|---|
| `409` | `{"error":"This user still has reservations or sessions on record, so the account cannot be deleted."}` |
| `502` | `{"error":"The delete could not be completed. Please try again."}` |

In practice the `409` is what you get for a user whose `sessions` rows still reference them. The
admin UI does not offer delete, and disabling accounts is the intended workflow.

---

## Roles

The lookup table behind `users.role_name`. **The primary key is `name`** — there is no `id` column.

**Role object** — `{ "name": "faculty", "description": "Teaching staff" }`

The role that matters is `admin`; the check is `role_name == "admin"`, case-insensitive.

### `GET /api/roles`

**Auth:** public. Array of all roles. This one works.

`user_roles.name` is the primary key, so individual role routes use `/api/roles/{name}`.

### `POST /api/roles`

**Auth:** admin. The body is whitelisted to `name` and `description`. Send
`{"name":"faculty","description":"Teaching staff"}`.

---

## Cross-cutting gotchas

Current server behavior and known limitations. Automated tests cover the security cases listed in
the test section below. Production database permissions and AWS settings still require live checks.

### 1. Private events are masked by the API

All three public event reads mask private details unless a valid faculty or admin session is
present. The public response still contains the reservation ID and time interval so the calendar can
display occupied time. Verify with a production request after deployment.

### 2. Disabled users cannot start or continue a session

Login rejects disabled accounts. Session resolution reloads the enabled account record and
invalidates sessions whose account is missing or disabled.

### 3. Double-booking is only prevented in the browser

There is no overlap check anywhere on the server. Two events can occupy the same slot. The overlap
rule lives entirely in `reservationValidation.js`, so anything calling the API directly — or a race
between two people booking simultaneously — can double-book.

*Fix:* an exclusion constraint on `events` (Postgres `EXCLUDE USING gist` on a `tstzrange`) is the
robust answer; a server-side check in `ReservationController` would catch the common case.

### 4. Database errors are not returned to clients

Reads and writes use generic error responses when Supabase fails. This avoids returning Postgres
constraint messages, table details, or Supabase response bodies to the browser.

### 5. `PATCH /api/events/{id}` requires the whole object

It re-validates as if it were a create. Send all eight required fields, not just what changed.

### 6. Sessions accumulate until cleanup is configured

Expired and invalidated rows are not automatically purged from `sessions`. Until a cleanup policy is
added, a user's session rows can prevent account deletion. The database hardening migration removes
old raw tokens once and signs users out.

### 7. `GET` by id returns `[]`, not `404`

Only `PATCH` and `DELETE` on `/events/{id}` return a real `404`. Every other by-id read returns a
`200` with an empty array. Check `array.length`, not the status.

### 8. There is no `DELETE /api/events/series/{groupId}`

Series deletion is N sequential requests from the browser, and is not atomic.

### 9. The 8 AM start of the day is not enforced

Only the 5:00 PM end is checked server-side. The API accepts a 6 AM booking; the UI just never
offers one.

---

## Verification

The backend test suite runs with `mvn clean verify`. It includes tests for private event masking,
faculty/admin access, disabled-account login rejection, session token hashing, exact-origin request
checks, and correct Supabase secret-key headers. These are local automated tests. Apply the database
migration and exercise the production API before treating live permissions or deployment behavior
as verified.
