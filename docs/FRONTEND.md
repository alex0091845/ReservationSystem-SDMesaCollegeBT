# Frontend Handover

Everything you need to take over the web app. Assumes you know JavaScript, HTML and CSS but nothing
about this project.

---

## 1. What the frontend actually is

**Plain HTML, CSS and vanilla JavaScript ES modules. No framework, no build step, no `npm install`,
no `node_modules`, no bundler.** The browser loads `js/main.js` as `<script type="module">` and the
import graph does the rest.

That has two practical consequences:

- **To run it, serve the folder.** Editing a file and refreshing is the entire dev loop.
- **Every import must be a real relative path with a `.js` extension** (`"./ui/modal.js"`, not
  `"./ui/modal"`). There's no resolver to be clever for you.

Three pages, three entry points:

| Page | Entry script | Who it's for |
|---|---|---|
| `index.html` | `js/main.js` | Everyone — the calendar. Anonymous visitors see public events and can check in; logged-in faculty see and manage their reservations |
| `login.html` | `js/login.js` | Sign in |
| `admin.html` | `js/admin.js` | Admins — manage users and any reservation |

---

## 2. Running it locally

```bash
cd frontend
python -m http.server 5500        # → http://localhost:5500
```

(Or the VS Code **Live Server** extension.) Opening `index.html` with `file://` will **not** work —
ES modules require http.

You also need the backend running (see `docs/BACKEND.md`), and the backend's
`APP_CORS_ALLOWED_ORIGIN_PATTERNS` must include `http://localhost:5500`.

### Pointing the frontend at the backend

`js/api.js` resolves the API base URL like this:

```js
const API_ORIGIN = window.RESERVATION_API_ORIGIN || window.location.origin;
const BASE_URL = `${API_ORIGIN}/api`;
```

Each of the three HTML files sets that global just before loading its module:

```html
<script>window.RESERVATION_API_ORIGIN = "";</script>
```

- **Empty** → call `/api` on the same origin as the page. Correct when the backend serves the
  frontend, and when you run both on `localhost` behind one origin.
- **Set to the backend origin** (e.g. `"https://api.example.com"`) → the split S3-frontend /
  EC2-backend deploy this project is designed for. **You must edit all three files** at deploy time
  (`index.html:197`, `admin.html:313`, `login.html:45`).

Cross-site cookies also need the backend on `AUTH_COOKIE_SAME_SITE=None` + `AUTH_COOKIE_SECURE=true`.

---

## 3. File map

```
frontend/
├── index.html          calendar page — also contains the markup for 3 modals
├── login.html
├── admin.html          also contains the markup for its 3 modals
├── js/
│   ├── api.js          ★ the ONLY file that talks to the backend
│   ├── main.js         ★ index.html controller — state, render loop, edit modal
│   ├── admin.js        admin.html controller — users, and reservations for a chosen user
│   ├── login.js        login form
│   ├── ui/             rendering + widgets (no fetch calls except through api.js)
│   │   ├── modal.js                 event-details modal, public check-in, CREATE-reservation modal
│   │   ├── reservationTimePicker.js ★ drag-select week grid used by every reservation form
│   │   ├── reservationDrafts.js     autosave engine + "save draft?" prompt
│   │   ├── monthView.js             the month calendar
│   │   ├── weekView.js              the week grid with positioned event blocks
│   │   ├── upcomingEvents.js        "upcoming" list
│   │   ├── myEvents.js              faculty's own reservations widget
│   │   ├── checkInEvents.js         today's events widget (anonymous visitors)
│   │   ├── eventCards.js            shared card builders + date/time formatting
│   │   ├── attendees.js             attendee list + count badge
│   │   ├── eventTypeOptions.js      fills a <select> with event types
│   │   └── actionMenu.js            the small pop-up "Delete ▾" menu
│   └── utils/
│       ├── dateUtils.js             calendar constants, date maths, formatting, colour classes
│       └── reservationValidation.js ★ client-side reservation rules (mirrors the backend)
├── styles/             main.css @imports the rest; admin.css and login.css are standalone
└── assets/             logo + favicons
```

Read in this order: `api.js` → `main.js` → `ui/modal.js`. The rest are leaves.

The layering rule the codebase follows: **`ui/` modules render and return DOM; they do not own
application state.** State lives in the page controller (`main.js` / `admin.js`), which passes data
down and receives callbacks back.

---

## 4. `api.js` — the network layer

One private `request(endpoint, method, data)` helper does all fetching:

- prefixes `BASE_URL`
- sends `credentials: "include"` — **required**, this is how the `session_id` cookie travels
- **8-second timeout** via `AbortController`
- parses JSON, falls back to raw text
- on a non-2xx response throws an `Error` carrying `error.status` and `error.data` — callers branch
  on `error.status === 401 / 403`

Everything else in the file is an exported wrapper: `getEvents`, `createEvent`, `createEvents`,
`updateEvent`, `deleteEvent`, `getEventTypes`, `getUsers`, `createUser`, `updateUser`,
`disableUser`, `enableUser`, `loginUser`, `logoutUser`, `getCurrentSession`, `getAttendees`,
`createAttendee`, `getReservationDraft`, `saveReservationDraft`, `deleteReservationDraft`,
`isUserDisabled`.

### The normalizers (why they exist)

A big chunk of `api.js` is defensive shape-fixing, because the data has drifted over the project's
life and the code accepts every historical shape:

- `normalizeEvent` — accepts `user_id` **or** `host_user_id`, `start`/`start_time`, `end`/`end_time`,
  and lifts the joined `users` object to `host_user`.
- `normalizeCollection` — accepts a bare array *or* `{events: […]}` / `{data: […]}` / `{items: …}` /
  `{records: …}`.
- `normalizeEventTypeValue` + `EVENT_TYPE_VALUE_ALIASES` — maps old values onto current ones
  (`study` → `Study_Group`, `office_hours` → `Other`, …). **This same alias table is duplicated in
  `ui/eventTypeOptions.js`** — change one, change both.
- `toBackendEvent` — the reverse: strips a reservation down to exactly the nine columns the backend
  accepts. **If you add a field to reservations, it must be added here or it will be silently
  dropped on save.**

### Creating reservations — three paths and two fallbacks

`createEvents(list)` validates locally, then tries in order:

1. **`POST /api/events/recurring`** — only if `buildRecurringCreateRequest` can prove every
   reservation is identical except for being an exact multiple of 7 days apart. It compresses them
   into one template + week ranges + excluded offsets. This is why booking 16 weeks of office hours
   is one small request instead of 16.
2. **`POST /api/events/batch`** — one request containing the array.
3. **One `POST /api/events` per reservation**, 8 at a time — only when the failure looks like a
   missing/unreachable endpoint rather than a rejected request: 404, 405, 501, 502, 503, 504, or a
   403 whose body is an HTML error page (a proxy blocking the route, not the API saying no). A real
   API error is re-thrown, never retried. If any request in a chunk fails, it **rolls
   back** by deleting the ones that succeeded; anything it can't delete is reported to the user by
   ID so they can clean up manually.

Single-reservation creates go through `createEvent`.

---

## 5. Auth and session handling

The real session is the **HttpOnly `session_id` cookie** — JavaScript cannot read it and doesn't
need to. `sessionStorage` holds only a **UI hint mirror**:

| Key | Purpose |
|---|---|
| `facultyLoggedIn` | show "Sign Out" instead of "Faculty Login", enable the Create button |
| `adminLoggedIn` | show the Admin Dashboard link |
| `currentUserId`, `currentUserEmail`, `currentUserRole` | render the name chip, decide what's editable |

**Treat `sessionStorage` as decoration, never as authorization.** Editing it in DevTools changes
what buttons appear and nothing else — every request is still checked server-side against the
cookie. On page load `main.js` calls `syncSessionFromBackend()` (`GET /api/session`); a 401 wipes
the mirror. `admin.js` goes further: `bootstrapAdminDashboard()` refuses to render and redirects to
`login.html` unless `GET /api/session` confirms the role is admin. (One deliberate exception in
`verifyAdminSession`: if the request fails with *no HTTP status at all* — a network error or the
8-second timeout — and the sessionStorage mirror says admin, it lets the page render. The data
requests behind it will still fail, and the server still rejects every write.)

Login flow (`login.js`): `POST /api/login` → verify with `GET /api/session` → write the
sessionStorage mirror → redirect to `admin.html` for admins, `index.html` for everyone else.

---

## 6. `main.js` — the calendar page

**Module-level state:**

```js
let reservedEvents = [];   // every event from the backend
let attendees = [];        // all check-ins (only loaded when logged in)
let eventTypes = [];
let currentUser = null;
let selectedFacultyReservation = null;
const state = { selectedDate, currentYear, currentMonth };
```

**The render model is deliberately dumb: one `renderAll()` redraws everything.** There is no
diffing and no reactivity — mutate state, call `renderAll()`. It repaints the nav, month calendar,
week view, upcoming list, and side widgets. Because event counts are small this is fast enough;
don't add a framework to "fix" it.

**Startup** (bottom of the file): `init()` binds listeners and does a first paint from empty state,
then `loadEvents()` fetches session → paints → fetches events/types/attendees in parallel with
`Promise.allSettled` → paints again. `allSettled` matters: one failing endpoint must not blank the
page.

**What each user sees**

| | Anonymous | Faculty | Admin |
|---|---|---|---|
| Calendar & week view | ✅ | ✅ | ✅ |
| "Today's events" check-in widget | ✅ | — | — |
| "My Events" widget | — | ✅ | — (uses the admin dashboard instead) |
| Create Reservation button | disabled | ✅ | ✅ |
| Clicking an event | details modal + check-in form | edit modal if they own it, else details | edit modal always |
| Admin Dashboard link | — | — | ✅ |

That routing is `openCalendarEvent()` → `canCurrentUserEditReservation()`, which is admin OR
host-id match OR host-email match.

**Editing a reservation.** The edit modal's markup lives in `index.html`
(`#facultyReservationModalOverlay`); the logic is in `main.js`. Submitting is not a plain PATCH: the
time picker can hold **several** blocks, so the first becomes a `PATCH` of the existing reservation
and any extra blocks become **new** reservations via `createEvents`. Everything is validated first
by `validateEditedReservations`, which excludes the reservation being edited from the overlap check
(otherwise it would always collide with itself) and then feeds each newly planned block back into
the list so the new blocks are checked against each other too.

**Deleting.** Single delete, or "Delete Recurring Series" when 2+ reservations share a
`recurrence_group_id`. Series delete loops one `DELETE` per reservation, sequentially. Both use
`window.confirm`.

---

## 7. `ui/modal.js` — three responsibilities in one file

Slightly confusingly named. It contains:

1. **`createModalController(elements)`** — exported; returns `openEventModal` / `closeEventModal`
   for the read-only **event details** modal.
2. **The public check-in form** inside that modal. Only shown to anonymous visitors
   (`facultyLoggedIn` is not set). Submits to `POST /api/attendees`, which is the one public write
   endpoint in the whole API.
3. **The whole "Create Reservation" modal** — not exported, wired up at module load via top-level
   `addEventListener` calls at the bottom of the file. This is why importing `modal.js` has side
   effects.

The create flow: collect the form → `buildReservationOccurrences` expands the recurrence checkbox
("repeat weekly for N weeks", generating a `recurrence_group_id` via `crypto.randomUUID`) →
`buildCreatableReservationPlan` validates each occurrence, and **for recurring bookings only** it
*skips* occurrences that overlap something existing rather than failing the whole booking, then
asks the user to confirm the skipped dates → `createEvents(...)` → dispatch a
`window` `CustomEvent("reservation:created")`.

**That custom event is how `modal.js` talks back to `main.js`** without a circular import —
`main.js` listens for it and merges the new reservations into `reservedEvents`. Worth knowing
before you go looking for a function call that isn't there.

---

## 8. `ui/reservationTimePicker.js` — the week grid

Used by all three reservation forms (create, faculty edit, admin edit). It replaces the old
start/end `<input type="datetime-local">` pair — those inputs still exist as **hidden fields** that
the picker keeps in sync, which is why the forms still validate with plain HTML `required`.

- A 7-day × 30-minute grid from **8 AM to 5 PM** (`CALENDAR_START_HOUR`/`CALENDAR_END_HOUR` in
  `dateUtils.js`).
- Click-drag to select; drag over an already-selected block to deselect. Multi-day drags produce one
  range **per day**, not one giant span.
- Selections are normalized: sorted, merged when they touch, and re-rendered.
- `createReservationTimePicker` returns `{ clear, getWeekStart, getRanges, render, setRange,
  setRanges, setWeekFromDate }`. `getRanges()` returns `[{start: Date, end: Date}, …]`.
- If any of its four required elements is missing it returns a **no-op picker** whose `getRanges()`
  is `[]`, so a page that doesn't have the markup won't crash.

---

## 9. `ui/reservationDrafts.js` — autosave

Half-typed reservation forms are saved **to the server** (`/api/reservation-drafts`), not to
localStorage, so a draft survives a different browser.

`createReservationDraftAutosave({...})` returns
`{ activate, deactivate, discard, hasChanges, markClean, saveNow, scheduleSave }`.

- `activate()` on modal open: loads any existing draft and replays it into the form.
- Any `input`/`change` on the form schedules a save **700 ms** later (debounced).
- Concurrent saves are serialized — if a save is in flight, the next one queues behind it rather
  than racing.
- On close, if `hasChanges()` is true, `showDraftExitPrompt()` builds a small dialog in JS offering
  **Save Draft / Discard Changes / Continue Editing**.
- A successful submit or an explicit discard calls `discard()`, which soft-deletes the draft
  server-side.
- The `#…DraftBadge` element shows when a stored draft exists.

Companion helpers `collectReservationFormDraft` / `applyReservationFormDraft` /
`clearReservationFormDraftFields` serialize and restore *any* form generically by walking
`form.elements`, so they work for all three modals unchanged.

---

## 10. `admin.js` — the admin dashboard

Same shape as `main.js`, different data. Left pane: searchable/filterable/sortable user list
(disabled users always sort last). Right pane: the selected user's details, Edit User /
Disable–Enable User buttons, and all of their reservations.

- **Users are never deleted from the UI** — "Disable User" PATCHes `enabled: false`. Disabled users
  can't create reservations (enforced in `reservationValidation.js` and in the UI). Admins cannot be
  disabled.
- On the user form, a **blank password means "keep the current one"** and the field is deleted
  before sending. The plaintext password is also deleted from the in-memory user list after a
  successful save.
- Reservation editing reuses the identical modal + time-picker + autosave + series-delete logic as
  `main.js`, against `#adminReservation*` element ids. **This is the largest duplication in the
  codebase** — the two `validateEditedReservations`, `getReservationSeries`, `deleteReservations`,
  `bindBackdropClose` and `removeReservations…State` functions are near-copies. If you refactor
  anything here, that's the highest-value target.

---

## 11. Validation — `utils/reservationValidation.js`

`validateReservationData({ reservationData, existingReservations, users, requireId })` returns
`{ isValid, message }`. It is the **client mirror of the backend rules** — it exists for fast, clear
error messages, *not* as a security control. The server re-checks everything.

Rules, in order:
1. `requireId` (edits only) — id present.
2. All eight required fields present: host, start, end, event type, description, title, department,
   access.
3. Both timestamps parse; end after start.
4. Start before **5:00 PM**, end not after **5:00 PM** (`CALENDAR_END_HOUR`).
5. Host is not a disabled user.
6. No overlap with an existing reservation — message reads `This reservation overlaps with "…"`.
   `ui/modal.js` matches on that prefix to decide which recurring occurrences it may silently skip,
   **so don't reword that message without updating `OVERLAP_VALIDATION_MESSAGE_PREFIX`.**

There is a vestigial "room" concept (`ROOM_FIELD_NAMES`: `room_id`, `roomId`, `room_name`, `room`,
`location`). No room column exists in the schema, so `isSameReservationRoom` always returns true and
the whole building behaves as one bookable space (BT-216). The hooks are there if rooms are ever added.

---

## 12. CSS

`styles/main.css` is only a list of `@import`s — `base`, `layout`, `navbar`, `panels`, `month-view`,
`upcoming-events`, `week-view`, `modal`, `responsive`, in that order. `admin.css` and `login.css`
are loaded separately by their pages.

Order matters: `responsive.css` is last so its media queries win. Event colours are **class-based**,
assigned by `getEventColorClass(eventType)` in `dateUtils.js`
(`study_group`→green, `meeting`→blue, `workshop`→orange, `social`→purple, everything else→red).
Adding an event type without adding a case there gives you red.

---

## 13. Common tasks

**Add a field to the reservation form**
1. Add the input to the modal markup in `index.html` (and `admin.html` if admins need it), with a
   `name`.
2. Read it in the submit handler (`ui/modal.js` for create, `main.js` / `admin.js` for edit).
3. Add it to `toBackendEvent` in `api.js` — **otherwise it is silently dropped**.
4. Add it to `REQUIRED_RESERVATION_FIELDS` in `reservationValidation.js` if required.
5. Add the matching `copyField` line in the backend's `ReservationController` (see `docs/BACKEND.md` §7).
   Autosave needs no change — it walks `form.elements` generically.

**Add a new API call** — add a wrapper to `api.js` using `request(...)`. Never call `fetch`
elsewhere; `credentials: "include"` and the timeout live in that one helper.

**Change the bookable hours** — `CALENDAR_START_HOUR` / `CALENDAR_END_HOUR` / `CALENDAR_END_LABEL`
in `utils/dateUtils.js`, **and** `RESERVATION_DAY_END` in the backend's `ReservationController`.
They are not shared; both must change.

**Debug** — everything logs to the console with `console.error("Could not …", error)`. The Network
tab plus those messages is usually enough. Check that requests carry the `session_id` cookie if you
see unexpected 401s.

---

## 14. Known gaps — please read before you change anything

1. **No tests and no build/lint step.** Nothing catches a typo except loading the page. Check all
   three pages after a change to shared code.
2. **`main.js` and `admin.js` duplicate ~200 lines** of reservation-edit logic (see §10).
3. **The event-type alias table exists twice** — `api.js` and `ui/eventTypeOptions.js`.
4. **`RESERVATION_API_ORIGIN` is hand-edited in three HTML files at deploy time.** Easy to forget
   one; symptom is one page working and another failing CORS. A deploy script would fix this.
5. **Series delete is sequential and not atomic** — deleting a 16-week series is 16 requests, and a
   failure part-way leaves the rest deleted.
6. **`isUserDisabled()` calls `getUsers()`**, which is admin-only, so it 403s for normal faculty.
   The error is caught and it returns `false`, so nothing breaks — but the check is effectively
   admin-only.
7. **`formatDateTimeLocalValue` is dead code** in both `main.js` and `admin.js`, left over from the
   `datetime-local` inputs the time picker replaced. `getSortValue` in `admin.js` also ignores its
   `sortField` argument, so the sort dropdown only really toggles direction.
8. **`window.confirm` is used for deletes and skipped-occurrence confirmation.** Fine, but it blocks
   the page and can't be styled — worth replacing with the same pattern as `showDraftExitPrompt()`
   if the UI is ever polished.
9. **The calendar shows private events to anonymous visitors**, because `GET /api/events` is public
   on the backend and returns everything. This is a backend decision — see `docs/BACKEND.md` §8.1.
