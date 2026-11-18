# Deploying to a DigitalOcean Droplet

This guide puts the whole site (frontend and backend) on one DigitalOcean Droplet, which is a small rented Linux server. It costs about **$6/month**. A domain name is optional while testing, and costs about $12/year later.

The database stays on Supabase. That means the Droplet holds no data of its own: if it breaks, you can delete it and build a new one from this guide.

**Who should read what:**
- **Setting up the server for the first time?** Follow Part 1 from top to bottom.
- **A teammate who needs to log in?** Go straight to [For teammates: connecting to the server](#for-teammates-connecting-to-the-server).
- **Something broke?** See [Troubleshooting](#troubleshooting).

---

## Contents

- **Overview**
  - [How it fits together](#how-it-fits-together)
  - [What you need first](#what-you-need-first)
- **Part 1: First-time server setup (admin)**
  1. [Create the Droplet](#1-create-the-droplet)
  2. [Choose a web address](#2-choose-a-web-address)
  3. [Lock down the server](#3-lock-down-the-server)
  4. [Add swap](#4-add-swap)
  5. [Install Docker and get the code](#5-install-docker-and-get-the-code)
  6. [Create the secrets file (.env)](#6-create-the-secrets-file-env)
  7. [Set up Caddy](#7-set-up-caddy)
  8. [Start it and check](#8-start-it-and-check)
- **Part 2: Giving teammates access**
  - [How SSH keys work](#how-ssh-keys-work)
  - [For teammates: connecting to the server](#for-teammates-connecting-to-the-server)
  - [For admins: adding a teammate](#for-admins-adding-a-teammate)
  - [Removing someone's access](#removing-someones-access)
- **Running the site**
  - [Day-to-day tasks](#day-to-day-tasks)
  - [Moving from sslip.io to a real domain](#moving-from-sslipio-to-a-real-domain)
- **Troubleshooting**
  - [SSH and login problems](#ssh-and-login-problems)
  - [Docker problems](#docker-problems)
  - [HTTPS and Caddy problems](#https-and-caddy-problems)
  - [App problems](#app-problems)
- [Cost summary](#cost-summary)

---

## How it fits together

```
Browser ──HTTPS──▶  Caddy (ports 80/443 on the Droplet)
                     ├─ /api/*           ──▶ backend container (Spring Boot, port 8080, not public)
                     └─ everything else  ──▶ files in frontend/

backend ──HTTPS──▶ Supabase
```

- **Caddy** is a small web server. It does three jobs: it serves the frontend files, passes `/api/...` requests to the backend, and gets a free HTTPS certificate automatically. You don't install it yourself. Docker downloads and runs it.
- **The backend** runs in Docker, built from the existing `backend/Dockerfile`. Its port is never opened to the internet, so the only way in is through Caddy.
- The frontend and API share one address (for example `https://reservations.example.com`). That's why the HTML files can keep `window.RESERVATION_API_ORIGIN = ""` and why no cookie or CORS changes are needed.

---

## What you need first

- A DigitalOcean account. The [GitHub Student Developer Pack](https://education.github.com/pack) has included DigitalOcean credit in past years, so check it first.
- An SSH key on your computer. See [How SSH keys work](#how-ssh-keys-work) if you don't have one.
- Your Supabase URL and API key. The current key is an `sb_secret` key, which can bypass all database rules, so treat it like a password.
- Optionally, a domain name. You can start without one (see [step 2](#2-choose-a-web-address)).

---

# Part 1: First-time server setup (admin)

## 1. Create the Droplet

In the DigitalOcean dashboard: **Create → Droplets**.

| Setting        | Choose                                                             |
|----------------|--------------------------------------------------------------------|
| Region         | San Francisco (closest to San Diego)                               |
| Image          | **Ubuntu 24.04 (LTS)**                                             |
| Size           | Basic → Regular → **$6/mo (1 GB RAM)**. See the memory note below. |
| Authentication | **SSH Key** (not password). Upload the contents of your `.pub` file. |
| Extras         | Turn on **Monitoring** (free). Backups aren't needed (no data lives here). |
| Hostname       | e.g. `reservations`                                                |

**Memory note:** Java plus the build step is tight on 1 GB. [Step 4](#4-add-swap) adds "swap" (spare memory on disk) to cover it. If builds still fail or the site feels slow, resize to the $12 (2 GB) plan. You can resize later in the dashboard without rebuilding.

Write down the Droplet's **IPv4 address**.

---

## 2. Choose a web address

The site needs a **name**, not just an IP address. HTTPS certificates are issued for names, and without HTTPS, login won't work, because the session cookie is set to "secure only". (You *could* turn that setting off, but then passwords and session cookies travel unencrypted. Don't.) Skip the IPv6 address too: it has the same problem, and many home and school networks can't reach IPv6 at all.

There are two options.

### Option 1: Testing, free, no setup: sslip.io

[sslip.io](https://sslip.io) is a free service: any name in the form `<your-ip-with-dashes>.sslip.io` points to that IP. If your Droplet IP is `203.0.113.5`, your address is:

```
203-0-113-5.sslip.io
```

There's nothing to sign up for or configure. Caddy gets a real HTTPS certificate for it, so the site behaves exactly like it will with a real domain. The downsides are the ugly address, and that it changes if you rebuild the Droplet with a new IP. That's fine for testing.

### Option 2: Before real users: a real domain

- **Check the GitHub Student Developer Pack first.** It has offered a free domain for a year (Namecheap `.me` and get.tech `.tech` have both been on the list). The offers change from year to year.
- **If you buy one**, expect about $10–12/year for a `.com`. **Cloudflare Registrar** charges what it costs them with no markup, and renewals cost the same as year one. **Porkbun** and **Namecheap** are also fine.
- **Watch out for "$1 first year" deals.** Domains like `.xyz` or `.online` often renew at $30 or more. Check the **renewal price**.
- **Or ask the college's IT department** for a subdomain like `reservations.sdmesa.edu`. It's free and looks official, but it can take a while.

Once you have a domain, add an **A record** wherever it's managed:

| Type | Name                                        | Value             |
|------|---------------------------------------------|-------------------|
| A    | `reservations` (or `@` for the bare domain) | the Droplet's IPv4 |

DNS changes can take up to an hour to work.

You can start with sslip.io and switch later. See [Moving from sslip.io to a real domain](#moving-from-sslipio-to-a-real-domain).

---

## 3. Lock down the server

Connect as root. This is the only time you'll do so:

```bash
ssh root@YOUR_DROPLET_IP
```

### Create your own user

Create a user with admin rights, and copy root's SSH key to it:

```bash
adduser deploy                      # pick a password; you'll need it for sudo
usermod -aG sudo deploy
rsync --archive --chown=deploy:deploy ~/.ssh /home/deploy
```

**Check that the key was copied.** If root had no key (for example, the Droplet was created with password login), there was nothing to copy, and you'll get `Permission denied (publickey)` later:

```bash
cat /home/deploy/.ssh/authorized_keys
```

This should print one line starting with `ssh-ed25519` (or `ssh-rsa`), and it should match your computer's `.pub` file. If it's empty or missing, add your key by hand. Paste your whole `.pub` line in place of the placeholder:

```bash
mkdir -p /home/deploy/.ssh
echo "PASTE_YOUR_FULL_PUBLIC_KEY_LINE_HERE" >> /home/deploy/.ssh/authorized_keys
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
```

The last three lines matter. SSH quietly ignores the file if anyone other than its owner can change it.

### Turn on the firewall

This only lets in SSH, HTTP and HTTPS:

```bash
ufw allow OpenSSH
ufw allow 80
ufw allow 443
ufw enable
```

### Turn off root login and password login

```bash
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh
```

> **Before you close the root window**, open a **new** terminal and check that `ssh deploy@YOUR_DROPLET_IP` works. If it doesn't, fix it from the root window that's still open.
>
> If you already closed it and are locked out, use DigitalOcean's web console: Droplet page → **Access** → **Launch Droplet Console**.

From now on, always log in as `deploy`.

Ubuntu installs security updates on its own by default ("unattended upgrades"), so no extra setup is needed for that.

---

## 4. Add swap

Swap is spare memory on disk. It stops the Java build from crashing on a 1 GB Droplet.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Check it worked with `free -h`. The "Swap" row should show 2.0Gi.

---

## 5. Install Docker and get the code

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 git
sudo usermod -aG docker deploy
```

> **Now log out and back in.** This step is required. Adding yourself to the `docker` group only takes effect at your next login. If you skip it, Docker commands fail with `permission denied while trying to connect to the Docker daemon socket`.
>
> ```bash
> exit
> ssh deploy@YOUR_DROPLET_IP
> groups          # should include "docker"
> ```

Then get the code:

```bash
sudo mkdir -p /opt/reservation
sudo chown deploy:deploy /opt/reservation
git clone https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT.git /opt/reservation
cd /opt/reservation
```

---

## 6. Create the secrets file (.env)

The backend reads its settings from a file called `.env` in `/opt/reservation`. First, print your site address:

```bash
curl -s http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address | tr . -; echo .sslip.io
```

This asks DigitalOcean for the Droplet's public IP and prints it as an sslip.io name, e.g. `203-0-113-5` and `.sslip.io`. Put the two together: `203-0-113-5.sslip.io`. If you have a real domain, use that instead.

Create the file:

```bash
nano .env
```

Paste this, filling in your Supabase values and your address:

```ini
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_API_KEY=sb_secret_...
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://203-0-113-5.sslip.io
AUTH_COOKIE_SECURE=true
```

Save with **Ctrl+O**, then Enter, then exit with **Ctrl+X**. Then make the file readable only by you:

```bash
chmod 600 .env
```

Things to know:
- **`APP_CORS_ALLOWED_ORIGIN_PATTERNS` must match the browser's address bar exactly:** `https://`, no trailing slash, no path. The backend refuses to start if it's empty, and login fails if it doesn't match.
- `.env` is already in `.gitignore`, so it won't be committed by accident. Never commit it.
- `.env` is written with `nano` rather than pasted into the terminal like the files below, so the Supabase key doesn't end up in your shell history.

---

## 7. Set up Caddy

Caddy needs two files in `/opt/reservation`: the `Caddyfile` (its rules) and `docker-compose.yml` (what Docker should run). You paste each command block below into the terminal in one go, so there's no fiddling with spacing in an editor.

### Set your address for this session

**For sslip.io**, this works out the name automatically:

```bash
SITE="$(curl -s http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address | tr . -).sslip.io"
echo "$SITE"
```

**For a real domain**, set it directly:

```bash
SITE="reservations.example.com"
```

`echo "$SITE"` should print your full address. If it prints only `.sslip.io`, stop: the IP lookup failed. `SITE` is forgotten when you log out, so rerun this if you come back later.

### Create the Caddyfile

```bash
cat > Caddyfile <<EOF
$SITE {
    encode gzip

    handle /api/* {
        reverse_proxy backend:8080
    }

    handle {
        root * /srv
        file_server
    }
}
EOF
cat Caddyfile
```

The first line of the printed file should show your real address. Here's what each part means:

| Line | What it does |
|------|--------------|
| `your-address { ... }` | These rules are for this address. Because it's a name, Caddy gets an HTTPS certificate for it automatically. |
| `encode gzip` | Compresses pages so they load faster. |
| `handle /api/*` | Sends any address starting with `/api/` to the backend container. `backend` is the container's name in `docker-compose.yml`, and `8080` is its port. |
| `handle` (the second one) | Everything else: serve files from `/srv`, which is where the `frontend/` folder appears inside the Caddy container. |

### Create docker-compose.yml

```bash
cat > docker-compose.yml <<'EOF'
services:
  backend:
    build: ./backend
    restart: unless-stopped
    env_file: .env
    expose:
      - "8080"          # reachable by Caddy only, not the internet

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./frontend:/srv:ro
      - caddy_data:/data       # keeps the HTTPS certificate between restarts
      - caddy_config:/config
    depends_on:
      - backend

volumes:
  caddy_data:
  caddy_config:
EOF
```

> `Caddyfile` and `docker-compose.yml` hold no secrets, so they could be committed to the repo. Only `.env` must stay off GitHub.

---

## 8. Start it and check

First check that the firewall allows web traffic:

```bash
sudo ufw status
```

You should see `80` and `443` listed as `ALLOW`. Caddy needs port 80 to prove to Let's Encrypt (the free certificate service) that it controls the address, and port 443 is where HTTPS traffic arrives.

Start everything:

```bash
docker compose up -d --build
```

The first run takes a few minutes while Maven downloads the backend's dependencies. Then watch Caddy get its certificate:

```bash
docker compose logs -f caddy
```

Look for a line containing **`certificate obtained successfully`**. It usually appears within a minute. Press **Ctrl+C** to stop watching. That doesn't stop Caddy.

Check the backend started:

```bash
docker compose ps                    # both should say "running"
docker compose logs backend          # look for "Started RoomReservationApplication"
curl https://$SITE/api/health
```

Finally, open `https://YOUR-ADDRESS` in a browser. You should see a padlock icon in the address bar, and you should be able to log in.

If any step fails, see [Troubleshooting](#troubleshooting).

---

# Part 2: Giving teammates access

## How SSH keys work

The server doesn't use passwords to log in. Instead, each person makes a **key pair** on their own computer: two files that belong together.

| File | What it is | Share it? |
|------|------------|-----------|
| `id_ed25519` | **Private key.** Proves you are you. | **Never.** Don't send it, upload it, or commit it. |
| `id_ed25519.pub` | **Public key.** One line of text starting with `ssh-ed25519`. | Yes. It's safe to paste in chat or email. |

Think of the public key as a **padlock** and the private key as **the only key that opens it**. You can hand padlocks to anyone. On the server, each user has a file called `~/.ssh/authorized_keys`: the list of padlocks on their door, one public key per line. When you run `ssh`, your computer proves it holds a matching private key, and the server lets you in.

Each person has their own key pair. Nobody shares private keys, including admins.

---

## For teammates: connecting to the server

### 1. Make your key pair (one time)

Open a terminal (PowerShell on Windows, Terminal on Mac) and run:

```
ssh-keygen -t ed25519
```

Press Enter (don't type anything) to accept the default location. You can set a passphrase (a password for the key itself) or press Enter to leave it blank. A passphrase is safer if your laptop is ever lost.

If it says the file already exists, you already have a key. Answer `n` and use the one you have.

### 2. Send your public key to the server admin

Print it:

```
# Windows (PowerShell)
type $HOME\.ssh\id_ed25519.pub

# Mac / Linux
cat ~/.ssh/id_ed25519.pub
```

Copy the whole line and send it to the admin. Make sure it's the file ending in **`.pub`**. The admin will tell you your username once you've been added.

### 3. Log in

```
ssh YOUR_USERNAME@SERVER_IP
```

The first time, it asks if you trust the server ("Are you sure you want to continue connecting?"). Type `yes`.

Once you're in, the app lives in `/opt/reservation`:

```bash
cd /opt/reservation
docker compose ps        # is everything running?
```

See [Day-to-day tasks](#day-to-day-tasks) for deploying and reading logs. Type `exit` to log out.

If you get `Permission denied (publickey)`, see [SSH and login problems](#ssh-and-login-problems).

---

## For admins: adding a teammate

Get the teammate's **public** key line first (see above). Log in as yourself (not root; root login is off), then pick one of the options below.

### Option A (recommended): a separate account per person

You can see who did what, and removing one person doesn't affect anyone else. Replace `maria` and the key line:

```bash
sudo adduser maria                     # set a password; she'll need it for sudo
sudo usermod -aG sudo,docker maria     # admin rights + can run docker
sudo mkdir -p /home/maria/.ssh
echo "ssh-ed25519 AAAA...her key... maria@laptop" | sudo tee -a /home/maria/.ssh/authorized_keys
sudo chown -R maria:maria /home/maria/.ssh
sudo chmod 700 /home/maria/.ssh
sudo chmod 600 /home/maria/.ssh/authorized_keys
```

She logs in with `ssh maria@SERVER_IP`, and needs the password you set to use `sudo`.

- To give someone access **without** admin rights, use `sudo usermod -aG docker maria` instead.
- Leaving out `docker` means they'll get the Docker "permission denied" error (see [Docker problems](#docker-problems)).

### Option B: simpler, everyone shares `deploy`

Add their key as a new line in `deploy`'s list:

```bash
echo "ssh-ed25519 AAAA...their key..." >> ~/.ssh/authorized_keys
```

They log in with `ssh deploy@SERVER_IP`. It's easy, but everyone looks the same in the logs, and they all share one sudo password.

---

## Removing someone's access

- **Option A:** `sudo deluser --remove-home maria`
- **Option B:** run `nano ~/.ssh/authorized_keys` and delete that person's line.

Either way, their private key stops working on the server right away.

---

# Running the site

## Day-to-day tasks

Run these in `/opt/reservation`.

| Task                          | Command                                           |
|-------------------------------|---------------------------------------------------|
| Deploy the latest `main`      | `git pull && docker compose up -d --build`        |
| Frontend-only change          | `git pull` (Caddy serves the files directly, so no restart is needed) |
| See backend logs              | `docker compose logs -f backend`                  |
| See web server logs           | `docker compose logs -f caddy`                    |
| Check what's running          | `docker compose ps`                               |
| Restart everything            | `docker compose restart`                          |
| After editing `.env`          | `docker compose up -d`                            |
| After editing `Caddyfile`     | `docker compose restart caddy`                    |
| Free up disk from old builds  | `docker image prune -f`                           |

Both containers restart on their own after a crash or a Droplet reboot (`restart: unless-stopped`).

---

## Moving from sslip.io to a real domain

1. Add the domain's A record (see [step 2](#2-choose-a-web-address)) and wait for it to work. `ping reservations.example.com` should show the Droplet's IP.
2. Rerun the three blocks in [step 7](#7-set-up-caddy) with `SITE="reservations.example.com"`. That updates the Caddyfile. (Rewriting `docker-compose.yml` is harmless; it doesn't change.)
3. Point `.env` at the new address:
   ```bash
   sed -i "s|^APP_CORS_ALLOWED_ORIGIN_PATTERNS=.*|APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://$SITE|" .env
   grep CORS .env
   ```
4. Apply both changes:
   ```bash
   docker compose up -d
   docker compose restart caddy
   ```
5. Watch `docker compose logs -f caddy` for `certificate obtained successfully`, then open the new address.

---

# Troubleshooting

## SSH and login problems

**`Permission denied (publickey)`**

The server didn't find a matching key in that user's `~/.ssh/authorized_keys`.

- **Did the key get copied at all?** As root or another admin, run `cat /home/USERNAME/.ssh/authorized_keys`. If the file is empty or missing, add the key with the commands in [step 3](#create-your-own-user). This happens when the Droplet was created with password login, so root had no key to copy.
- **Are the owner and permissions right?** Rerun the `chown` and two `chmod` lines from step 3. SSH ignores the file if anyone else can change it.
- **Was the right file sent?** It must be the whole line from the `.pub` file.
- **Is the computer offering a different key?** Run `ssh -v USERNAME@SERVER_IP` and look for `Offering public key` lines. If it isn't the key that was added, choose it explicitly: `ssh -i ~/.ssh/id_ed25519 USERNAME@SERVER_IP`.
- **Is the username right?** It's the one the admin created, not your laptop's username.

**Locked out completely** (root login is off and no user works): use DigitalOcean's web console. Droplet page → **Access** → **Launch Droplet Console**.

---

## Docker problems

**`permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock`**

Your user isn't in the `docker` group yet, or was added but hasn't logged in again since.

```bash
groups                              # is "docker" listed?
sudo usermod -aG docker $USER       # if not, add yourself
exit                                # then log back in; this part is required
```

After logging back in, `groups` should include `docker`. If you were using the `SITE` variable, set it again (see [step 7](#set-your-address-for-this-session)).

Don't work around this by putting `sudo` in front of every Docker command, or by changing permissions on `docker.sock`. The first leaves files owned by root; the second gives every user full control of the server.

**`address already in use` when starting**

Something else is already using port 80 or 443. A common cause is the backend still running from an earlier `docker run -p 80:...`. Run `docker ps` to find it, then `docker stop <name>` and `docker rm <name>`.

**Build stops with "Killed"**

Out of memory. Check swap is on with `free -h` (see [step 4](#4-add-swap)), or resize the Droplet to 2 GB.

---

## HTTPS and Caddy problems

**Caddy logs show certificate errors, or the browser shows a certificate warning**

- Check `sudo ufw status` shows `80` and `443` as `ALLOW`.
- Check whether a firewall in the DigitalOcean dashboard (**Networking → Firewalls**) blocks 80 or 443.
- **Real domain:** the A record may not point at the Droplet yet. DNS changes can take up to an hour.
- **sslip.io:** check the Caddyfile's first line matches the Droplet's current IP, with dashes instead of dots.

**The Caddyfile's first line is just `{` or `.sslip.io {`**

`SITE` was empty when you created the file. Set it again (see [step 7](#set-your-address-for-this-session)), rerun the Caddyfile block, then `docker compose restart caddy`.

---

## App problems

| What you see | Likely cause |
|--------------|--------------|
| Backend keeps restarting | A missing value in `.env`, usually `APP_CORS_ALLOWED_ORIGIN_PATTERNS` or a Supabase value. `docker compose logs backend` names it. |
| Pages load but login fails | `APP_CORS_ALLOWED_ORIGIN_PATTERNS` doesn't exactly match the browser's address bar. Check with `grep CORS .env`. It needs `https://` and no trailing slash. |
| `/api/...` returns 502 | Backend is down or still starting. Check `docker compose ps` and `docker compose logs backend`. |
| Changed `.env` but nothing happened | Run `docker compose up -d` to apply it. |
| Changed `Caddyfile` but nothing happened | Run `docker compose restart caddy` to apply it. |

When asking for help, share the output of `docker compose logs backend` or `docker compose logs caddy`. Remove any Supabase key first if one appears.

---

## Cost summary

| Item                      | Cost            |
|---------------------------|-----------------|
| Droplet (1 GB)            | $6/month        |
| Droplet (2 GB, if needed) | $12/month       |
| sslip.io address          | Free            |
| Domain name (optional)    | ~$10–15/year (check the Student Pack first) |
| HTTPS certificate         | Free (Caddy + Let's Encrypt) |
| Supabase                  | Unchanged       |
