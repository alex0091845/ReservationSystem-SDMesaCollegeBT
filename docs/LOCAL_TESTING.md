# Local testing

This guide starts the full reservation app on Windows. The launcher uses the repository's existing
`frontend/` and `backend/` directories. It does not make local copies or require edits to production
HTML or Java files.

The local frontend is available at `http://localhost:5500`. Its server forwards `/api` requests to
the Spring Boot backend on port `8080`. The browser therefore uses the same-origin API setting as
the CloudFront site.

## Requirements

Use Windows 10 or 11, PowerShell, Git for Windows, Windows Package Manager (`winget`), and a web
browser. The launcher installs or downloads Java 17, Python, and Maven if they are not available.
The first run also downloads the backend's Maven dependencies.

If Git is not installed, open PowerShell and run:

```powershell
winget install --id Git.Git --exact --source winget --accept-source-agreements --accept-package-agreements
```

Restart PowerShell after Git installs. If `winget` is not available, install **App Installer** from
the Microsoft Store, then open a new PowerShell window.

## Get the project

Clone the branch that contains the local launcher. The current branch is
`feature/local-testing-setup`:

```powershell
git clone --branch feature/local-testing-setup --single-branch https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT.git
Set-Location .\ReservationSystem-SDMesaCollegeBT
```

If the setup has been merged into `main`, clone `main` instead by replacing the branch name in the
clone command.

## Configure the test database key

The launcher currently points to the `reservation-system-test` Supabase project at
`https://zstoxaqftyswronvdvxz.supabase.co`. Use a server-only `sb_secret` key from that test project.
Do not use the production key. The backend key bypasses RLS and must never be placed in frontend
code.

By default, the launcher asks for the key securely each time it starts. For convenience, a local
settings file can hold the key:

1. From the repository root, run `notepad .\local-testing\local-settings.ps1`.
2. Add this line, replacing the placeholder with the test project's server-only key:

   ```powershell
   $testSupabaseApiKey = 'PASTE_TEST_PROJECT_SB_SECRET_HERE'
   ```

3. Save the file and close Notepad.

`local-testing/.gitignore` excludes this settings file from Git. The file is plain text, so the ignore
rule prevents accidental commits but does not encrypt the key. Keep it on the local computer. To
remove the saved key, delete `local-testing/local-settings.ps1`; the launcher will prompt for it
again.

## Start the app

From the repository root, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\local-testing\start-local.ps1
```

The first run may install Java and Python through `winget`, download Maven to
`%LOCALAPPDATA%\ReservationSystem\tools`, and download Maven dependencies. Accept the package
installer prompts. If a new tool is not available immediately, close PowerShell, open it again, and
run the command again.

The launcher starts the frontend and backend, then opens the frontend automatically. The local
backend health check is available at:

- `http://localhost:8080/api/health`

Keep the PowerShell window open while testing. Press **Ctrl+C** in that window to stop the backend;
the launcher also stops its local frontend server and removes the temporary Python server script.

## Make and view changes

Edit files in `frontend/` or `backend/`, which are the same source directories used for production.
After changing HTML, CSS, or JavaScript, save the file and refresh the browser. The server does not
provide automatic live reload. After changing Java code, stop the launcher with Ctrl+C and start it
again so Spring Boot recompiles the backend.

The local app uses these settings for the backend process:

| Setting | Local value |
|---|---|
| Supabase URL | The test project URL configured in `local-testing/start-local.ps1` |
| Supabase API key | The test project's server-only key, entered at startup or read from the ignored settings file |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | `http://localhost:5500` |
| `SERVER_PORT` | `8080` |
| `AUTH_COOKIE_SECURE` | `false`, because local HTTP does not use TLS |
| `AUTH_COOKIE_SAME_SITE` | `Lax` |
| `AUTH_SESSION_HOURS` | `8` |

These values are set for the local backend run. They do not change the EC2 environment file or the
production CloudFront or S3 configuration.

## What stays separate from production

The launcher, its ignore rule, and the optional key file are contained in `local-testing/`. The
launcher reads `frontend/` and `backend/` directly instead of maintaining separate source copies.
It does not change those source files. Maven writes normal build output to the ignored
`backend/target/` folder and caches downloaded dependencies outside the repository.

Production deployment syncs `frontend/` to S3, so it does not upload `local-testing/`. The EC2
systemd service runs the backend from its production environment file and does not depend on this
launcher. Removing `local-testing/` removes the local launcher and optional key file; it does not
remove or change the frontend, backend, or production runtime.

## Troubleshooting

- If the launcher says port `5500` or `8080` is already in use, stop the other local server using
  that port, then run the launcher again.
- If it says Java, Python, or Maven is missing after installation, restart PowerShell and rerun the
  script.
- If WinGet reports that a package is already installed and no upgrade is available, the launcher now
  checks whether the required command works and looks for the JDK in its standard install folders.
  If Java is still unavailable, restart PowerShell and run the launcher again so Windows can refresh
  the Java installation paths.
- If `/api/health` does not load, check the PowerShell output for a backend startup error. The
  backend needs network access to the test Supabase project.
- If the page loads but API requests fail, open DevTools Network and confirm the request URL starts
  with `http://localhost:5500/api/`. The local frontend server forwards that route to port `8080`.
- Do not use `file://` to open an HTML page. ES modules require the frontend server.
