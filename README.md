# ReservationSystem-SDMesaCollegeBT

A room reservation system for the Business & Technology (BT) building at **San Diego Mesa College**, built by the computer science students attending Mesa College.

The app lets users browse and reserve rooms/events, manage attendees and event types, and gives admins a management view. It has a static frontend and a Spring Boot backend backed by Supabase (PostgreSQL).

> **New to the project?** If you are new to web development, start with
> [`docs/LEARNING.md`](docs/LEARNING.md) — prerequisites, learning resources, good first
> tasks, and the traps specific to this codebase. Otherwise start with the handover docs:
> [`docs/BACKEND.md`](docs/BACKEND.md) and [`docs/FRONTEND.md`](docs/FRONTEND.md) — how each half
> works, how to run it, common tasks, and known gaps. Endpoint-level detail (request bodies,
> responses, error codes, curl examples) lives in [`docs/API.md`](docs/API.md). Security controls
> and deployment checks are in [`docs/SECURITY.md`](docs/SECURITY.md). For the full local setup,
> follow [`docs/LOCAL_TESTING.md`](docs/LOCAL_TESTING.md).

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
| Backend   | Java 17, Spring Boot 4.1.1, Maven                |
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
mvn clean verify
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
| `SUPABASE_API_KEY`                  | ✅       | None    | Current EC2 setup uses an `sb_secret` key. Server-only; bypasses RLS, so backend authorization is required |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS`  | ✅       | None    | Comma-separated exact frontend origins, e.g. `https://dsowhvg574z7c.cloudfront.net,http://localhost:5500`. Wildcards and URL paths are rejected |
| `SERVER_PORT`                       |          | `8080`  | Port the backend binds to                                                   |
| `AUTH_COOKIE_SECURE`                |          | `true`  | Keep `true` in production; set `false` only for local http testing          |
| `AUTH_COOKIE_SAME_SITE`             |          | `Lax`   | Session cookie policy. Use `Lax` for the current CloudFront `/api/*` same-origin route. `None` requires `AUTH_COOKIE_SECURE=true` |
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

For the full application, use the [local testing guide](docs/LOCAL_TESTING.md). Its local server
serves the existing frontend and forwards `/api` requests to the backend, so the production source
files work locally without changing their API configuration.

For static layout work only, the frontend can be served without the backend:

```bash
cd frontend
python -m http.server 5500          # → http://localhost:5500
```

(Or use the VS Code **Live Server** extension.) API requests will not work through a basic static
server because it does not forward `/api` requests to Spring Boot.

### Pointing the frontend at the backend

`js/api.js` resolves the backend base URL as:

```js
const API_ORIGIN = window.RESERVATION_API_ORIGIN || window.location.origin;
const BASE_URL = `${API_ORIGIN}/api`;
```

Each HTML page (`index.html`, `admin.html`, `login.html`) contains a config block **before** its module script:

```html
<!-- Backend API origin. Leave empty for the CloudFront /api/* route to EC2. -->
<script>window.RESERVATION_API_ORIGIN = "";</script>
```

- **Current deploy** (S3 frontend and EC2 API behind one CloudFront domain): leave it empty. CloudFront routes `/api/*` to EC2. The local testing server proxies the same path to port 8080.
- **Direct cross-site API**: set it to the backend origin in all three HTML files and configure the exact frontend origin in the backend.

Whatever origin you set here must also be listed in `APP_CORS_ALLOWED_ORIGIN_PATTERNS`. A direct cross-site API also needs `AUTH_COOKIE_SAME_SITE=None` and `AUTH_COOKIE_SECURE=true`.

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

For the current CloudFront setup, keep `window.RESERVATION_API_ORIGIN` empty in all three pages. CloudFront routes `/api/*` to EC2. The S3 command syncs only `frontend/`, so it does not upload `local-testing/` or its local settings.

**Backend → EC2**

The production environment file is `/etc/reservation-backend.env`. See [Backend Handover](docs/BACKEND.md#ec2-environment-file) for details. Keep the file on EC2 and out of source control.

```bash
# on the EC2 instance
git pull
cd backend
mvn clean verify
java -jar target/*.jar          # with SUPABASE_* / CORS env vars exported
```

Or with Docker:

```bash
cd backend
docker build -t reservation-backend .
docker run -d -p 8080:8080 --env-file .env reservation-backend
```

Keep the exact CloudFront origin listed in `APP_CORS_ALLOWED_ORIGIN_PATTERNS`. The current distribution routes `/api/*` to EC2, so the browser calls the CloudFront origin and `AUTH_COOKIE_SAME_SITE=Lax` is appropriate. See [`docs/SECURITY.md`](docs/SECURITY.md) for the database migration and deployment checks.

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
