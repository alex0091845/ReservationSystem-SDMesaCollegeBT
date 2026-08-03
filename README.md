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
- A **Supabase** project (the backend talks to it over Supabase's REST API)

---

## Running the backend

From the `backend/` folder:

```bash
cd backend
```

Set the environment variables below first, then run:

```bash
mvn spring-boot:run
```

### Building a runnable jar (e.g. for EC2)

```bash
mvn clean package
java -jar target/*.jar
```

### Running with Docker

A multi-stage `Dockerfile` lives in `backend/`:

```bash
cd backend
docker build -t reservation-backend .
docker run -p 8080:8080 \
  -e SUPABASE_URL=... -e SUPABASE_API_KEY=... \
  -e APP_CORS_ALLOWED_ORIGIN_PATTERNS=... \
  reservation-backend
```

The API listens on **http://localhost:8080** by default (override with `SERVER_PORT`).
Liveness probe: `GET /api/health` → `{"status":"ok"}` (public, no auth).

---

## Environment variables

Set these before starting the backend. `APP_CORS_ALLOWED_ORIGIN_PATTERNS` has **no default** — the app fails to start if it is unset (fail closed), so misconfiguration can never silently open CORS to all origins.

| Variable                            | Required | Default | Description                                                                 |
|-------------------------------------|:--------:|---------|-----------------------------------------------------------------------------|
| `SUPABASE_URL`                      | ✅       | —       | Supabase project URL (Dashboard → Project Settings → API)                   |
| `SUPABASE_API_KEY`                  | ✅       | —       | Supabase key — use the **anon** key with Row Level Security enabled, **not** the service-role key |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS`  | ✅       | —       | Comma-separated allowed frontend origins, e.g. `https://your-bucket.s3-website.amazonaws.com,http://localhost:5500`. Must list the exact deployed frontend origin (no `*` with credentials) |
| `SERVER_PORT`                       |          | `8080`  | Port the backend binds to                                                   |
| `AUTH_COOKIE_SECURE`                |          | `true`  | Keep `true` in production; set `false` only for local http testing          |
| `AUTH_COOKIE_SAME_SITE`             |          | `Lax`   | Session cookie SameSite policy. For a cross-site frontend (S3) → backend (EC2) setup, use `None` (which also requires `AUTH_COOKIE_SECURE=true`) |
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

Each HTML page (`index.html`, `admin.html`, `login.html`) contains a config block **before** its module script:

```html
<!-- Backend API origin. Leave empty to call /api on the same origin as this page;
     set to the backend's origin when the frontend is hosted separately (e.g. S3 → EC2). -->
<script>window.RESERVATION_API_ORIGIN = "";</script>
```

- **Same-origin deploy** (frontend served by the backend): leave it empty.
- **Split deploy** (frontend on S3, backend on EC2 — the intended setup): set it to the backend origin, e.g. `"https://api.your-domain.com"`, in all three HTML files at deploy time.

Whatever origin you set here must also be listed in the backend's `APP_CORS_ALLOWED_ORIGIN_PATTERNS`, and for cross-site cookies the backend needs `AUTH_COOKIE_SAME_SITE=None` + `AUTH_COOKIE_SECURE=true`.

---

## API overview

All routes are under `/api`. Sessions are cookie-based (`session_id`).

| Method(s)              | Path                          | Purpose                          |
|------------------------|-------------------------------|----------------------------------|
| `GET`                  | `/api/health`                 | Liveness probe (public)          |
| `POST`                 | `/api/login`                  | Log in, sets the session cookie  |
| `POST`                 | `/api/logout`                 | Invalidate the session           |
| `GET`                  | `/api/session`                | Current logged-in user           |
| `GET/POST/PATCH/DELETE`| `/api/events`                 | Reservations / events            |
| `POST`                 | `/api/events/recurring`       | Compact recurring reservations   |
| `GET/POST/DELETE`      | `/api/reservation-drafts`     | Autosaved reservation drafts     |
| `GET`                  | `/api/events/public`          | Publicly visible events          |
| `GET/POST/…`           | `/api/attendees`              | Attendees (incl. `/by-event/{id}`, `/by-user/{id}`) |
| `GET/POST/PATCH/DELETE`| `/api/event-types`            | Event types                      |
| `GET/POST/…`           | `/api/users`                  | Users                            |
| `GET/…`                | `/api/roles`                  | User roles                       |

---

## Database

- **Schema:** `erd.sql` — tables (`users`, `user_roles`, `events`, `event_types`, `attendees`, `sessions`, `reservation_drafts`).
- **Seed data:** `testData.sql`.

Apply them to your Supabase/PostgreSQL instance (e.g. via the Supabase SQL editor) to set up the schema and sample data.

---

## Deployment

There is no CI yet — deploys are manual.

**Frontend → S3**

```bash
aws s3 sync frontend/ s3://<your-bucket> --delete
```

Before syncing, set `window.RESERVATION_API_ORIGIN` in the three HTML files to the deployed backend origin (see [Pointing the frontend at the backend](#pointing-the-frontend-at-the-backend)).

**Backend → EC2**

```bash
# on the EC2 instance
git pull
cd backend
mvn clean package
java -jar target/*.jar          # with SUPABASE_* / CORS env vars exported
```

Or with Docker:

```bash
cd backend
docker build -t reservation-backend .
docker run -d -p 8080:8080 --env-file .env reservation-backend
```

Keep the deployed frontend's origin listed in the backend's `APP_CORS_ALLOWED_ORIGIN_PATTERNS`, and use `AUTH_COOKIE_SAME_SITE=None` + `AUTH_COOKIE_SECURE=true` for the cross-site S3 → EC2 setup.

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
