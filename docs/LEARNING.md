# Learning Path

**Read this first if you are new to web development.** The other three docs
([`BACKEND.md`](BACKEND.md), [`FRONTEND.md`](FRONTEND.md), [`API.md`](API.md)) explain *what this
code does* — but they assume you already know what a REST endpoint, a dependency injection
container, or an ES module is. This file teaches those prerequisites and points at the exact place
each one shows up in our codebase.

You do **not** need to read all of this before contributing. Section 1 gets you running today.
After that, follow whichever track matches the work you picked up.

---

## Contents

1. [Day one — get it running](#1-day-one--get-it-running)
2. [Foundations everyone needs](#2-foundations-everyone-needs)
3. [Frontend track](#3-frontend-track)
4. [Backend track](#4-backend-track)
5. [Database track](#5-database-track)
6. [Concept → where it lives in this repo](#6-concept--where-it-lives-in-this-repo)
7. [Good first tasks](#7-good-first-tasks)
8. [Traps specific to this codebase](#8-traps-specific-to-this-codebase)
9. [How to debug things here](#9-how-to-debug-things-here)

---

## 1. Day one — get it running

The single best way to learn this codebase is to make the calendar appear in your own browser.
Full instructions are in [`BACKEND.md` §2](BACKEND.md) and [`FRONTEND.md` §2](FRONTEND.md); this is
the short version plus the things that will actually block you.

The frontend alone needs no build step and no backend:

```bash
cd frontend
python3 -m http.server 5500      # then open http://localhost:5500
```

The page will load and the calendar will render. Every network call will fail, because there is no
backend yet — that is expected, and it is already a useful place to work on CSS and layout.

**Three things that will block a beginner on the backend, in the order you will hit them:**

1. **You need a Supabase project.** The backend has no local database — it talks to Supabase over
   HTTP. You need `SUPABASE_URL` and `SUPABASE_API_KEY` before it will start.
2. **`APP_CORS_ALLOWED_ORIGIN_PATTERNS` has no default and the app refuses to boot without it.**
   That is deliberate (see [§8](#8-traps-specific-to-this-codebase)), not a bug. Set it to
   `http://localhost:5500`.
3. **There is no seed data in the repo.** `README.md` mentions `testData.sql`, but only `erd.sql`
   exists. You must create at least one user row with a real BCrypt `password_hash`, or you cannot
   log in. See [§7](#7-good-first-tasks) — fixing this is a great first contribution.

Also check your Java version. The project targets **Java 17**; Spring Boot 3.2.4 officially supports
17–21. If `mvn -v` reports a much newer JDK, install 17 or 21 and point `JAVA_HOME` at it rather
than fighting warnings.

---

## 2. Foundations everyone needs

Whatever you work on, these four come first.

### Git and the GitHub flow

We work on short-lived branches cut from `main` and merge back through pull requests. If
`git rebase` or "detached HEAD" scares you, that is normal and worth fixing early.

- [Pro Git, chapters 2–3](https://git-scm.com/book/en/v2) — free, official, and the only Git book
  you need. Chapter 3 (branching) is the one that makes it click.
- [GitHub flow](https://docs.github.com/en/get-started/using-github/github-flow) — the exact
  branch → PR → merge cycle this project uses.
- Video: search **"Git and GitHub for Beginners — Crash Course"** on the *freeCodeCamp.org*
  channel. About an hour, and covers everything we do day to day.

### How the web actually works

Every bug you will chase is a request that did not do what you expected.

- [MDN: HTTP overview](https://developer.mozilla.org/en-US/docs/Web/HTTP) — skim the overview, then
  read the pages on methods and status codes properly.
- [MDN: HTTP request methods](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Methods) —
  GET / POST / PATCH / DELETE are the four this API uses.
- [MDN: HTTP response status codes](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status)
  — you will care most about 400, 401, 403, 404 and 500. [`API.md`](API.md) documents which
  endpoint returns which.
- Video: search **"HTTP Crash Course & Exploration"** on the *Traversy Media* channel.

### JSON

Every request and response in this system is JSON.

- [MDN: Working with JSON](https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/Scripting/JSON)

### Client–server architecture

Our frontend and backend are two separate programs that only speak over HTTP. The frontend is
**static files** — no server-side rendering, no templating. Read
[`BACKEND.md` §1](BACKEND.md) for the one-paragraph version.

---

## 3. Frontend track

Our frontend is deliberately plain: no React, no build step, no npm. That is unusual today and it is
a good thing for learning — everything you see in the browser is a file you can read.

### HTML and CSS

- [MDN: Structuring content with HTML](https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/Structuring_content)
- [MDN: CSS styling basics](https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/Styling_basics)
- [CSS Grid guide](https://css-tricks.com/snippets/css/complete-guide-grid/) and
  [Flexbox guide](https://css-tricks.com/snippets/css/a-guide-to-flexbox/) from CSS-Tricks — the two
  best cheat sheets on the internet, and both are used heavily in
  `frontend/styles/modal.css`.
- Video: search **"CSS Grid Crash Course"** or **"Flexbox Crash Course"** on *Traversy Media*.

### JavaScript

- [MDN: JavaScript Guide](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide) — the
  reference you will keep open.
- [javascript.info](https://javascript.info/) — the best free structured JS course. Parts 1 and 2
  cover everything used in this repo.
- Video: search **"JavaScript Crash Course For Beginners"** on *Traversy Media*, or the
  *freeCodeCamp.org* full JavaScript course if you want the long version.

### The four JS concepts this codebase leans on hardest

1. **ES modules** (`import` / `export`) — every file in `frontend/js/` is a module.
   [MDN: JavaScript modules](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules).
   Note this is why the frontend must be *served* over `http://` and cannot be opened as a
   `file://` path.
2. **`fetch` and promises** — the entire network layer, `frontend/js/api.js`, is built on it.
   [MDN: Using the Fetch API](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch).
3. **`async` / `await`** — used in nearly every function in `api.js`.
   [MDN: Making asynchronous programming easier](https://developer.mozilla.org/en-US/docs/Learn_web_development/Extensions/Async_JS/Promises).
4. **DOM manipulation** — we build UI by creating elements in JavaScript, with no framework. Read
   `frontend/js/ui/reservationTimePicker.js` for a compact real example.
   [MDN: Introduction to the DOM](https://developer.mozilla.org/en-US/docs/Web/API/Document_Object_Model/Introduction).

### Cookies and sessions

Login here is cookie-based, not token-based. Understanding this saves hours.

- [MDN: Using HTTP cookies](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Cookies) — pay
  attention to `HttpOnly`, `Secure` and `SameSite`; all three are configurable in our
  `application.properties`.
- Then read [`FRONTEND.md` §5](FRONTEND.md) and [`BACKEND.md` §4](BACKEND.md) for how we use them.

---

## 4. Backend track

### Java

You need ordinary Java — classes, interfaces, generics, exceptions. You do **not** need threads,
streams or lambdas to be productive here.

- [dev.java — official Java tutorials](https://dev.java/learn/)
- Video: search **"Java Full Course for Beginners"** on the *freeCodeCamp.org* or *Programming with
  Mosh* channels.

### Maven

Maven is the build tool. `backend/pom.xml` declares dependencies and how to package the app.

- [Maven in 5 minutes](https://maven.apache.org/guides/getting-started/maven-in-five-minutes.html)
- [Maven getting started guide](https://maven.apache.org/guides/getting-started/index.html)

The only commands you need day to day:

```bash
mvn spring-boot:run     # run the app
mvn clean package       # build the jar
mvn compile             # just check it compiles
```

### Spring Boot

This is the biggest conceptual jump for a beginner. Spring does a lot of invisible work, and the
magic is confusing until you know its name.

- [Spring Guide: Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/) — do
  this one by hand. An hour here is worth ten hours of reading.
- [Spring Boot reference documentation](https://docs.spring.io/spring-boot/index.html) — reference,
  not a tutorial. Use it to look things up.
- Video: search **"Spring Boot Quick Start"** on the *Java Brains* channel — a beginner-focused
  series that explains the annotations rather than just using them.

**The specific magic to understand, in order:**

1. **Annotations** — `@RestController`, `@Component`, `@Service`. These tell Spring "make one of
   these at startup." See the comment on `SupabaseClient.java`, which explains it in one line.
2. **Dependency injection** — why `ReservationController`'s constructor takes a `SupabaseClient`
   without ever calling `new SupabaseClient()`.
3. **Request mapping** — how `@GetMapping("/{id}")` turns an HTTP request into a method call.
4. **Interceptors** — `AuthInterceptor` runs *before* controllers and is the gate that enforces
   login. [`BACKEND.md` §4](BACKEND.md) explains ours.

### Jackson

We do not use DTO classes. We manipulate JSON trees directly with `JsonNode` and `ObjectNode`, which
is unusual and worth reading about specifically.

- [Jackson `ObjectMapper` guide (Baeldung)](https://www.baeldung.com/jackson-object-mapper-tutorial)
- [Jackson `JsonNode` guide (Baeldung)](https://www.baeldung.com/jackson-json-node-tree-model)

### Password hashing

- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
  — read the BCrypt section. Explains why `AuthService` never stores or compares raw passwords.

---

## 5. Database track

### SQL

- [PostgreSQL tutorial](https://www.postgresql.org/docs/current/tutorial.html) — official, and
  Supabase *is* PostgreSQL.
- [Select Star SQL](https://selectstarsql.com/) — free, interactive, genuinely good for beginners.
- Read `erd.sql` in the repo root alongside it. It is only ~80 lines and defines every table.

### Supabase and PostgREST

This is the part most tutorials will not prepare you for: **our backend does not use JDBC or
Hibernate.** It calls Supabase's auto-generated REST API over HTTP. That is why `pom.xml` has no
database driver.

- [Supabase docs](https://supabase.com/docs)
- [PostgREST documentation](https://postgrest.org/en/stable/) — specifically the tables and views
  page, which explains the query syntax you will see all over our controllers.

Once you know PostgREST, strings like this stop looking cryptic:

```
events?id=eq.5&select=*,users(first_name,last_name)
```

It means `SELECT *, joined user names FROM events WHERE id = 5`.
There is a short version of this in [`BACKEND.md` §9](BACKEND.md).

### Row Level Security

- [Supabase: Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
  — matters because we use the **anon** key, not the service-role key.

---

## 6. Concept → where it lives in this repo

Read the concept, then immediately read our code that uses it. This table is the fastest path from
"I read a tutorial" to "I understand this project."

| Concept | Read this file | What to notice |
|---|---|---|
| ES modules | `frontend/js/api.js` (top) | every UI file imports from here |
| `fetch` + async/await | `frontend/js/api.js` → `request()` | one wrapper all calls go through |
| DOM building, no framework | `frontend/js/ui/reservationTimePicker.js` | the whole week grid is built in JS |
| Client-side validation | `frontend/js/utils/reservationValidation.js` | mirrors the backend's rules |
| CSS Grid | `frontend/styles/modal.css` | the time-picker grid |
| Spring REST controller | `backend/.../controller/ReservationController.java` | the biggest, richest example |
| Dependency injection | any controller's constructor | nothing ever calls `new` |
| Spring interceptor | `backend/.../config/AuthInterceptor.java` | the auth gate |
| Jackson JSON trees | `ReservationController.normalizeEventInput()` | whitelists fields by hand |
| HTTP client | `backend/.../config/SupabaseClient.java` | Java's built-in `HttpClient` |
| Cookie sessions | `backend/.../services/SessionService.java` | creation, expiry, invalidation |
| BCrypt | `backend/.../services/AuthService.java` | hash comparison, never plaintext |
| CORS | `backend/.../config/CorsConfig.java` | and why it fails closed |
| SQL schema | `erd.sql` | every table and index |
| Docker multi-stage build | `backend/Dockerfile` | build stage vs runtime stage |

---

## 7. Good first tasks

These are drawn from the real "Known gaps" lists in [`BACKEND.md` §8](BACKEND.md) and
[`FRONTEND.md` §14](FRONTEND.md) — they are genuine, wanted work, not busywork. Roughly easiest
first.

**Good for a first PR**

1. **Write `testData.sql`.** `README.md` references it but it does not exist, so nobody can log in
   without hand-writing rows. Needs a few users (with real BCrypt hashes), event types and events.
   Teaches: SQL inserts, the schema, BCrypt. *Unblocks every future contributor* — highest value on
   this list.
2. **Delete the dead code.** `formatDateTimeLocalValue` is unused in both `main.js` and `admin.js`.
   Teaches: safe searching before deleting.
3. **Fix `getSortValue` in `admin.js`**, which ignores its `sortField` argument, so the admin sort
   dropdown only toggles direction.

**Once you are comfortable**

4. **Fix `/api/roles/{id}`.** It queries `user_roles?id=eq.{id}`, but that table's primary key is
   `name`, not `id`. GET-by-id, PATCH and DELETE on roles are all broken. Teaches: PostgREST query
   syntax, reading a schema.
5. **De-duplicate the event-type alias table**, which exists in both `api.js` and
   `ui/eventTypeOptions.js`. Teaches: module boundaries.
6. **Replace `window.confirm`** for deletes with the existing in-page prompt pattern
   (`showDraftExitPrompt()`). Teaches: DOM, promises, existing-pattern reuse.

**Meaty**

7. **Add the first tests.** There is no `backend/src/test` directory at all. Even three tests over
   `ReservationController`'s validation rules would be a real contribution. Teaches: JUnit, Spring
   Boot testing, and why the "no tests" gap keeps appearing in these docs.
8. **Clean up expired sessions.** Rows in `sessions` accumulate forever.
9. **Prevent double-booking on the backend.** The frontend checks for overlaps, but the API does
   not — two clients can book the same room. See [`API.md`](API.md) gotchas.

Before starting any of these, read the "Known gaps" section it came from. Several have subtleties
the one-line summary here leaves out.

---

## 8. Traps specific to this codebase

Things that will confuse you and are **not** your fault.

1. **The backend refuses to start without `APP_CORS_ALLOWED_ORIGIN_PATTERNS`.** This is deliberate
   "fail closed" design: a misconfigured deploy must never silently allow every origin. Set it, do
   not remove the check.
2. **There is no build step for the frontend, and that is on purpose.** Do not add npm, a bundler,
   or a framework without discussing it first.
3. **`frontend/styles/modal.css` has mixed line endings** (both CRLF and LF). Some editors
   "helpfully" normalize the whole file on save, which turns a three-line change into a 1400-line
   diff. If your PR shows a whole-file rewrite, this is why — check your editor's line-ending
   setting before committing.
4. **`RESERVATION_API_ORIGIN` is hand-edited in three HTML files** at deploy time. If one page works
   and another fails with a CORS error, you missed one.
5. **The frontend and backend duplicate validation rules on purpose.** The frontend copy is for a
   fast error message; the backend copy is the one that actually protects the data. If you change a
   rule, change both — `reservationValidation.js` and `ReservationController`.
6. **`GET /api/events` is public and returns everything**, including private events. Known gap, not
   an accident of your change.
7. **Nothing catches a typo except loading the page.** No tests, no linter, no compiler for the
   frontend. After touching shared code, open all three pages: `index.html`, `admin.html`,
   `login.html`.

---

## 9. How to debug things here

Learn these three tools before you need them.

### Browser DevTools

`F12`, or right-click → Inspect. The two tabs that matter:

- **Console** — JavaScript errors appear here. A blank page almost always means an error on this
  tab.
- **Network** — every request, its status code, and its response body. When "saving does not work,"
  this tab tells you whether the request was even sent, and what the server said.

[Chrome DevTools documentation](https://developer.chrome.com/docs/devtools) ·
[MDN: What went wrong? Troubleshooting JavaScript](https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/Scripting/What_went_wrong)

### curl

To test the API without the frontend in the way. Sessions are cookie-based, so you need a cookie
jar — [`API.md`](API.md) opens with a working login example.

```bash
curl -c cookies.txt -X POST http://localhost:8080/api/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"..."}'

curl -b cookies.txt http://localhost:8080/api/events
```

### Reading a stack trace

Java stack traces are long but read top to bottom: the first line is the error, and the first line
mentioning `com.reservation` is almost always where your bug is. Ignore the Spring frames in
between.

Note that the API deliberately does not return error details to clients
(`server.error.include-message=never`), so the real message is in the **server console**, not in the
browser.

---

## Where to go after this

Once you can run both halves and have shipped one small PR, read the three handover docs properly,
in this order:

1. [`FRONTEND.md`](FRONTEND.md) or [`BACKEND.md`](BACKEND.md) — whichever half you are working on
2. The other one, skimmed, so you know what the other side expects
3. [`API.md`](API.md) — reference. Do not read it end to end; look things up in it.

If something in those docs is wrong or unclear, fixing it is itself a good PR.
