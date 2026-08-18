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
`SameSite` come from the backend's env vars.

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

Reads pass PostgREST's response through untouched, so **`GET` always returns a JSON array**, even
when you fetch by id. A missing row is `[]`, not a 404.

```bash
GET /api/events/999   →   200 []
```

Writes send `Prefer: return=representation` to PostgREST, so a successful `POST`/`PATCH` returns
**an array containing the row(s) written**, not a bare object. The one exception is
`POST /api/login`, which returns a single user object.

`?select=…` joins mean some responses carry a nested object — events carry `users`, attendees carry
`events`, users carry `user_roles`.

### ⚠️ Status codes are not uniform

This is the single most surprising thing about this API, so it is worth learning up front. Some
endpoints pass the database's status code through; others always answer `200` and put the database's
error in the body.

| Endpoint group | Behaviour |
|---|---|
| `POST`/`PATCH` on `/events`, `/events/batch`, `/events/recurring`, `/reservation-drafts` | **Status propagated.** A rejected write returns the real 4xx. |
| All `DELETE` except `/reservation-drafts/{id}` | **Inspected properly** — 204 / 409 / 502 (see `DeleteOutcome`). |
| Every `GET` | **Always `200`**, even if the database errored. |
| `POST`/`PATCH` on `/users`, `/attendees`, `/event-types`, `/roles` | **Always `200`**, even if the write was rejected. |

So on those last two rows, "did it work?" must be answered by **looking at the body**, not the
status. A rejected write comes back as `200` with a PostgREST error object:

```json
{ "code": "23505", "details": null, "hint": null,
  "message": "duplicate key value violates unique constraint \"users_email_key\"" }
```

A successful write is always an **array**. So: *if the body is an object with a `code` field, the
write failed.* See [Cross-cutting gotchas](#cross-cutting-gotchas).

---

## Quick reference

★ = ownership checked inside the controller.

| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| `GET` | `/api/health` | public | Liveness probe |
| `POST` | `/api/login` | public | Log in, set session cookie |
| `POST` | `/api/logout` | public | Invalidate session, clear cookie |
| `GET` | `/api/session` | public | Who am I |
| `GET` | `/api/events` | public | All events |
| `GET` | `/api/events/{id}` | public | One event |
| `GET` | `/api/events/by-user/{userId}` | public | Events hosted by a user |
| `GET` | `/api/events/public` | public | Only `is_public=true` |
| `POST` | `/api/events` | login ★ | Create one |
| `POST` | `/api/events/batch` | login ★ | Create many |
| `POST` | `/api/events/recurring` | login ★ | Create a weekly series compactly |
| `PATCH` | `/api/events/{id}` | login ★ | Update one |
| `DELETE` | `/api/events/{id}` | login ★ | Delete one (+ its check-ins) |
| `GET` | `/api/reservation-drafts` | login | Your drafts |
| `POST` | `/api/reservation-drafts` | login ★ | Save/update a draft |
| `DELETE` | `/api/reservation-drafts/{id}` | login | Discard a draft |
| `GET` | `/api/attendees` | login | All check-ins |
| `GET` | `/api/attendees/{id}` | login | One check-in |
| `GET` | `/api/attendees/by-event/{eventId}` | login | Check-ins for an event |
| `POST` | `/api/attendees` | **public** | Check in to an event |
| `PATCH` | `/api/attendees/{id}` | login | Update a check-in |
| `DELETE` | `/api/attendees/{id}` | login | Delete a check-in |
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
| `GET` | `/api/roles/{id}` | public | ⚠️ broken — see [Roles](#roles) |
| `POST` | `/api/roles` | admin | Create role |
| `PATCH` | `/api/roles/{id}` | admin | ⚠️ broken |
| `DELETE` | `/api/roles/{id}` | admin | ⚠️ broken |

---

## Auth

### `POST /api/login`

**Auth:** public

**Request**

```json
{ "email": "ada@sdmesa.edu", "password": "secret" }
```

Both fields required. Email is matched exactly as sent — the frontend lowercases it first
(`login.js`), so store emails lowercase.

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
- ⚠️ **`enabled` is not checked.** A disabled user can still log in and use the API. Disabling is
  enforced only in the browser. See [gotcha 2](#2-disabled-users-are-only-disabled-in-the-browser).

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

**Auth:** login — you must be the host, or an admin.

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

Ownership is checked against the **stored** event first, then against the **submitted**
`host_user_id` — so you can't take over someone's event, and you can't hand yours to someone else
unless you're an admin.

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

A **soft** delete: it stamps `discarded_at` (and `updated_at`) rather than removing the row, and
every read filters on `discarded_at IS NULL`. The update is scoped to `id` **and** your `user_id`,
so you cannot discard someone else's draft.

> ⚠️ Unlike every other DELETE, this one does **not** check the result — it returns `204`
> unconditionally. Deleting an id that doesn't exist, or isn't yours, also returns `204`.

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

**Auth:** login. Every check-in in the system, each with its event's title.

### `GET /api/attendees/{id}`

**Auth:** login. Array with 0 or 1 element.

### `GET /api/attendees/by-event/{eventId}`

**Auth:** login. All check-ins for one event — this is what the attendee list in the reservation
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

**Success — `200`** with the created row.

**Errors**

| Status | Body |
|---|---|
| `400` | `{"error":"event_id is required"}` |
| `400` | `{"error":"first_name is required"}` |
| `400` | `{"error":"Malformed request body"}` |

⚠️ A database-level rejection (e.g. `event_id` pointing at a nonexistent event) still returns
`200` with a PostgREST error object in the body. See
[gotcha 4](#4-some-writes-report-200-even-when-they-failed).

```bash
curl -X POST http://localhost:8080/api/attendees \
  -H 'Content-Type: application/json' \
  -d '{"event_id":12,"first_name":"Grace","last_name":"Hopper"}'
```

**Note:** there is no rate limiting and no duplicate check. The same person can check in repeatedly,
and anyone who knows an event id can post check-ins to it.

---

### `PATCH /api/attendees/{id}`

**Auth:** login. Same whitelist as create; nothing is required, but the payload can't be empty
(`400 {"error":"No updatable fields provided"}`). Returns `200` with the updated row.

### `DELETE /api/attendees/{id}`

**Auth:** login. `204` on success.

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

⚠️ **The body is not whitelisted** — it is forwarded to PostgREST exactly as received, unlike every
other write endpoint. Send only real columns:

```json
{ "event_type": "Lecture", "description": "Scheduled class" }
```

Both return `200` with the written row **and also `200` if the write was rejected** — check the body.

### `DELETE /api/event-types/{eventType}`

**Auth:** admin. `204` on success.

| Status | Body |
|---|---|
| `409` | `{"error":"Reservations are still using this event type, so it cannot be deleted."}` |
| `502` | `{"error":"The delete could not be completed. Please try again."}` |

The `409` is the common case: you cannot remove a type while any event references it. Repoint or
delete those events first.

**Note:** `{eventType}` is interpolated into the query string without URL-encoding, so a type
containing spaces or `&` will misbehave. Stick to simple identifiers like `Study_Group`.

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

⚠️ **A duplicate email returns `200`**, not a conflict — with a PostgREST `23505` error object in the
body. See [gotcha 4](#4-some-writes-report-200-even-when-they-failed).

### `PATCH /api/users/{id}`

**Auth:** admin. Partial update — send only what changes. `400 {"error":"No updatable fields
provided"}` if nothing recognisable is sent.

Disabling and enabling an account is just this endpoint:

```bash
curl -b jar -X PATCH http://localhost:8080/api/users/5 \
  -H 'Content-Type: application/json' -d '{"enabled":false}'
```

⚠️ Setting `enabled: false` stops the **browser** from letting that user book. It does not stop the
API. See [gotcha 2](#2-disabled-users-are-only-disabled-in-the-browser).

### `DELETE /api/users/{id}`

**Auth:** admin. `204` on success.

| Status | Body |
|---|---|
| `409` | `{"error":"This user still has reservations or sessions on record, so the account cannot be deleted."}` |
| `502` | `{"error":"The delete could not be completed. Please try again."}` |

In practice the `409` is what you get for any user who has ever logged in, because their `sessions`
rows reference them and sessions are never purged. **The admin UI does not offer delete at all** —
it disables accounts instead, which is the intended workflow.

---

## Roles

The lookup table behind `users.role_name`. **The primary key is `name`** — there is no `id` column.

**Role object** — `{ "name": "faculty", "description": "Teaching staff" }`

The role that matters is `admin`; the check is `role_name == "admin"`, case-insensitive.

### `GET /api/roles`

**Auth:** public. Array of all roles. This one works.

### ⚠️ `GET`/`PATCH`/`DELETE` `/api/roles/{id}` are broken

These build the query `user_roles?id=eq.{id}`, but **`user_roles` has no `id` column**. PostgREST
rejects the query with `42703 column user_roles.id does not exist`. Observed behaviour:

| Request | Result |
|---|---|
| `GET /api/roles/1` | `200` with `{"code":"42703","message":"column user_roles.id does not exist"}` |
| `PATCH /api/roles/1` | `200` with the same error object |
| `DELETE /api/roles/1` | `502 {"error":"The delete could not be completed. Please try again."}` |

The delete at least fails loudly, because it goes through `DeleteOutcome`; the other two look like
successes to any client checking only the status code.

Nothing in the frontend calls them, which is why this has gone unnoticed. **To fix**, change the
path variable to the role name and the filter to `name=eq.`, e.g.:

```java
@GetMapping("/{name}")
public ResponseEntity<String> getByName(@PathVariable String name) {
    return ResponseEntity.ok(supabase.get("user_roles?name=eq." + name));
}
```

### `POST /api/roles`

**Auth:** admin. Body is **not whitelisted** — forwarded as-is. Send `{"name":"…","description":"…"}`.

---

## Cross-cutting gotchas

Behaviours that surprise people. All of these are current as of this writing and verified against a
running instance.

### 1. Private events are readable by anyone

`GET /api/events`, `/events/{id}` and `/events/by-user/{id}` are public and return **every** event,
including `is_public: false` ones, with host names attached. `is_public` is respected by the UI, not
by the API. `/api/events/public` exists and filters correctly, but nothing calls it.

*Fix:* either point the anonymous calendar at `/api/events/public`, or remove `/api/events` from the
`GET` allowlist in `AuthInterceptor.isPublicEndpoint`.

### 2. Disabled users are only disabled in the browser

Nothing on the backend reads `users.enabled` — it is written by `UserController` and never read
again. A user with `enabled: false` can still log in and still create, edit and delete their own
reservations. The restriction lives in `frontend/js/utils/reservationValidation.js` and in the
disabled Create button.

Verified: logging in as a user with `"enabled": false` returns `200` with a valid session cookie,
and that session can create events.

*Fix:* reject in `AuthService.authenticate`, or in `AuthInterceptor` after the session resolves.

### 3. Double-booking is only prevented in the browser

There is no overlap check anywhere on the server. Two events can occupy the same slot. The overlap
rule lives entirely in `reservationValidation.js`, so anything calling the API directly — or a race
between two people booking simultaneously — can double-book.

*Fix:* an exclusion constraint on `events` (Postgres `EXCLUDE USING gist` on a `tstzrange`) is the
robust answer; a server-side check in `ReservationController` would catch the common case.

### 4. Some writes report `200` even when they failed

`POST`/`PATCH` on `/users`, `/attendees`, `/event-types` and `/roles` discard the database's status
code and always answer `200`. A rejected write therefore looks successful to any client that only
checks the status — which is exactly what `frontend/js/api.js` does. The visible symptom: creating a
user with a duplicate email appears to succeed in the admin UI and the row is added to the list,
until you reload.

**How to tell:** a successful write returns an **array**; a failure returns an **object** with
`code` / `message` / `details` / `hint`.

*Fix:* switch those controllers to `postResponse` / `patchResponse` and the `fromSupabase` helper,
the way `ReservationController` already does.

### 5. `GET` never reports database failures

Every read is wrapped in `ResponseEntity.ok(...)`, so if Supabase returns an error the client gets
`200` with an error object where it expected an array. The frontend's `normalizeCollection` turns
that into `[]`, so **a database outage looks like "no data"** rather than an error.

### 6. `PATCH /api/events/{id}` requires the whole object

It re-validates as if it were a create. Send all eight required fields, not just what changed.

### 7. Sessions accumulate forever

Expired and invalidated rows are never purged from `sessions`. This also means `DELETE /api/users/{id}`
will `409` for anyone who has ever logged in.

### 8. `GET` by id returns `[]`, not `404`

Only `PATCH` and `DELETE` on `/events/{id}` return a real `404`. Every other by-id read returns a
`200` with an empty array. Check `array.length`, not the status.

### 9. There is no `DELETE /api/events/series/{groupId}`

Series deletion is N sequential requests from the browser, and is not atomic.

### 10. The 8 AM start of the day is not enforced

Only the 5:00 PM end is checked server-side. The API accepts a 6 AM booking; the UI just never
offers one.

---

## How this reference was verified

Rather than reading the controllers and inferring, the behaviour above was observed against a
running backend:

- The jar was built (`mvn clean package`) and run against a stand-in PostgREST server that returns
  controlled responses — successes, `23503` foreign-key violations, `23505` duplicate keys, `42703`
  missing-column errors, and generic failures.
- Every endpoint was then exercised with curl as an anonymous caller, a non-admin session, and an
  admin session, and the observed status codes and bodies recorded.

That is how the status-code caveats, the exact error strings, the check ordering, the 5 PM boundary
behaviour, the DST handling, and gotchas 1–4 and 10 were confirmed rather than assumed. If you
change a controller, the quickest way to re-check this document is to repeat that exercise.

