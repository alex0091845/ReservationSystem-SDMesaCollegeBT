# ReservationSystem-SDMesaCollegeBT

A room reservation system for the Business & Technology (BT) building at **San Diego Mesa College**, built by the computer science students attending Mesa College.

The app lets users browse and reserve rooms/events, manage attendees and event types, and gives admins a management view. It has a static frontend and a Spring Boot backend backed by Supabase (PostgreSQL).

---

## Project structure

This is a monorepo with two independently deployed halves:

```
.
├── frontend/     Static site (HTML/CSS/vanilla JS) — deployed to S3
│   ├── index.html, login.html, admin.html
│   ├── js/       api client + UI modules
│   └── styles/
├── backend/      Spring Boot REST API — deployed to EC2
│   └── src/main/java/com/reservation/
├── erd.sql       Database schema (tables, indexes)
├── testData.sql  Sample seed data
└── README.md
```

- **Frontend** → hosted on **S3** (static, no build step).
- **Backend** → runs on **EC2** (Spring Boot, port 8080 by default).
- **Database** → **Supabase** (PostgreSQL). The backend talks to it over Supabase's REST API.

---

## Tech stack

| Layer     | Tech                                             |
|-----------|--------------------------------------------------|
| Frontend  | HTML, CSS, vanilla JavaScript (ES modules)       |
| Backend   | Java 17, Spring Boot 3.2.4, Maven                |
| Database  | Supabase (PostgreSQL)                            |
| Auth      | Cookie-based sessions (`session_id` cookie)      |

---

## Prerequisites

- **Java 17+** and **Maven** (for the backend)
- A modern browser and any static file server (for the frontend)
- A **Supabase** project (only needed for the real profile — the mock profile needs no database)

---

## Running the backend

From the `backend/` folder:

```bash
cd backend
```

### Mock mode (no database)

Serves canned data from `src/main/resources/mock_data.json` — great for frontend development without a Supabase project.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

Mock controllers are annotated `@Profile("mock")`; the real controllers are `@Profile("!mock")`, so exactly one set is active at a time.

### Real mode (Supabase)

The default profile. Requires the environment variables below to be set first.

```bash
mvn spring-boot:run
```

### Building a runnable jar (e.g. for EC2)

```bash
mvn clean package
java -jar target/*.jar          # add --spring.profiles.active=mock for mock mode
```

The API listens on **http://localhost:8080** by default (override with `SERVER_PORT`).

---

## Environment variables (real mode)

Set these before running the default profile. Mock mode ignores them.

| Variable                            | Required | Default | Description                                                                 |
|-------------------------------------|:--------:|---------|-----------------------------------------------------------------------------|
| `SUPABASE_URL`                      | ✅       | —       | Supabase project URL (Dashboard → Project Settings → API)                   |
| `SUPABASE_API_KEY`                  | ✅       | —       | Supabase API key                                                            |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS`  | ✅       | —       | Comma-separated allowed frontend origins, e.g. `https://your-bucket.s3-website.amazonaws.com,http://localhost:5500` |
| `SERVER_PORT`                       |          | `8080`  | Port the backend binds to                                                   |
| `AUTH_COOKIE_SECURE`                |          | `true`  | Set `false` for local http testing                                          |
| `AUTH_COOKIE_SAME_SITE`             |          | `Lax`   | Session cookie SameSite policy                                              |
| `AUTH_SESSION_HOURS`                |          | `8`     | Session lifetime in hours                                                   |

Example (macOS/Linux):

```bash
export SUPABASE_URL="https://xxxx.supabase.co"
export SUPABASE_API_KEY="your-key"
export APP_CORS_ALLOWED_ORIGIN_PATTERNS="http://localhost:5500"
export AUTH_COOKIE_SECURE=false     # local http
mvn spring-boot:run
```

---

## Running the frontend

The frontend is static — serve the `frontend/` folder with any static server:

```bash
cd frontend
python -m http.server 5500          # → http://localhost:5500
```

(Or use the VS Code **Live Server** extension.)

### Pointing the frontend at the backend

`js/api.js` resolves the backend base URL as:

```js
const API_ORIGIN = window.RESERVATION_API_ORIGIN || window.location.origin;
const BASE_URL = `${API_ORIGIN}/api`;
```

- By default it calls `/api` on the **same origin** as the page.
- To point at a backend on a different host (e.g. your EC2 instance during local dev), set a global before `api.js` loads, for example in your HTML:

  ```html
  <script>window.RESERVATION_API_ORIGIN = "http://localhost:8080";</script>
  ```

Make sure that origin is included in the backend's `APP_CORS_ALLOWED_ORIGIN_PATTERNS`.

---

## API overview

All routes are under `/api`. Sessions are cookie-based (`session_id`).

| Method(s)              | Path                          | Purpose                          |
|------------------------|-------------------------------|----------------------------------|
| `POST`                 | `/api/login`                  | Log in, sets the session cookie  |
| `POST`                 | `/api/logout`                 | Invalidate the session           |
| `GET`                  | `/api/session`                | Current logged-in user           |
| `GET/POST/PATCH/DELETE`| `/api/events`                 | Reservations / events            |
| `GET`                  | `/api/events/public`          | Publicly visible events          |
| `GET/POST/…`           | `/api/attendees`              | Attendees (incl. `/by-event/{id}`, `/by-user/{id}`) |
| `GET/POST/PATCH/DELETE`| `/api/event-types`            | Event types                      |
| `GET/POST/…`           | `/api/users`                  | Users                            |
| `GET/…`                | `/api/roles`                  | User roles                       |

---

## Database

- **Schema:** `erd.sql` — tables (`users`, `user_roles`, `events`, `event_types`, `attendee`, `sessions`) and indexes.
- **Seed data:** `testData.sql`.

Apply them to your Supabase/PostgreSQL instance (e.g. via the Supabase SQL editor) to set up the schema and sample data.

---

## Deployment

There is no CI yet — deploys are manual.

**Frontend → S3**

```bash
aws s3 sync frontend/ s3://<your-bucket> --delete
```

**Backend → EC2**

```bash
# on the EC2 instance
git pull
cd backend
mvn clean package
java -jar target/*.jar          # with SUPABASE_* / CORS env vars exported
```

Keep the deployed frontend's origin listed in the backend's `APP_CORS_ALLOWED_ORIGIN_PATTERNS`.

---

## Contributing

Work off short-lived feature branches cut from `main` and merge back via PR:

```bash
git checkout main && git pull
git checkout -b feature/your-thing
# …commit…
git push -u origin feature/your-thing
```

Delete branches once they're merged so `main` stays the single source of truth.
