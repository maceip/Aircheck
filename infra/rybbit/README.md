# Rybbit log sink — Aircheck

Self-hosted [Rybbit](https://github.com/rybbit-io/rybbit) analytics acting as the durable, off-Mac
log/event sink for the Aircheck app + rig. Deployed to a DigitalOcean droplet and built to run
unattended: it auto-updates and self-heals for as long as the droplet is paid.

## Live deployment

| Item | Value |
|---|---|
| Dashboard | **https://logs.relay.stare.network** |
| Droplet | `logs-rybbit` (ID 623754237), region `nyc3` |
| Size | `s-4vcpu-8gb` — 8 GB / 4 vCPU / 160 GB (~$48/mo) |
| OS | Ubuntu 24.04 LTS |
| Public IPv4 | `104.236.6.170` |
| DNS | `logs.relay.stare.network` A → 104.236.6.170 (DO zone `relay.stare.network`) |
| SSH | `ssh -i ~/.ssh/id_ed25519 root@104.236.6.170` (DO key `mb`) |
| Stack dir | `/opt/rybbit` |

Stack: ClickHouse + Postgres + Redis + backend + client + Caddy (auto Let's Encrypt TLS).

## Why it is "forget-about-it"

- **Auto-update:** `containrrr/watchtower` (daily) rolling-restarts the app images
  (`backend`, `client`, `caddy`) when newer images publish. Databases stay on pinned versions so an
  unattended update never triggers a surprise major migration. `DOCKER_API_VERSION=1.44` is set
  because Watchtower 1.7.1 otherwise negotiates an API version modern Docker rejects.
- **Self-heal:** every service is `restart: unless-stopped`; Docker is enabled at boot.
- **TLS:** Caddy provisions and renews certificates automatically.
- **Backups:** root cron `15 3 * * *` runs `/opt/rybbit-deploy/backup.sh` — daily Postgres dump +
  config/secrets, weekly ClickHouse snapshot, bounded rotation (7 daily / 4 weekly) under
  `/opt/rybbit-backups`.
- **OS security:** `unattended-upgrades` enabled.
- **Log rotation:** backend/client Docker logs capped at 100 MB × 5.
- **Never runs out of disk:** ClickHouse `events`/`bot_events`/`session_replay_events` carry a
  **90-day TTL** (auto-expire); a daily **disk-guard** cron (`/opt/rybbit-deploy/disk-guard.sh`,
  04:00) prunes build cache + unused images and, at ≥85% disk, force-expires ClickHouse data and
  trims oldest backups. Data volumes are never pruned. Backups are bounded (7 daily / 4 weekly).
- **Signup disabled:** `DISABLE_SIGNUP=true` (backend rejects new accounts; client rebuilt to hide
  the signup UI). Single-owner instance.

## One-time setup (you own the account)

1. Open **https://logs.relay.stare.network/signup** and create your account.
2. Create an organization + a **site** (e.g. `aircheck`) → note the numeric **site_id**.
3. Settings → Organization → **Organization API Keys** → create a key (full-access or
   `ingest:write`). Copy it immediately (shown once).

## Log shipping from the rig — ACTIVE

The rig forwards its generation log to Rybbit via `POST /api/track` (`_RybbitLogHandler` in
`sync-trainer/home-daemon/ota_server.py`). The ingest key stays **server-side on the rig** — never in
the app. Config lives in the **gitignored** `.aircheck-rig/env` file, which the `aircheck-rig`
supervisor reads into `ota_server`'s environment (`rigEnvFile` in `tools/aircheck-rig/main.go`):

```bash
# .aircheck-rig/env  (gitignored — never commit)
RYBBIT_URL=https://logs.relay.stare.network
RYBBIT_SITE_ID=419e72a9c59f
RYBBIT_API_KEY=rb_org_…   # ingest:write org key
```

Shipping is gated: with `RYBBIT_URL` + `RYBBIT_SITE_ID` unset, nothing is sent. The local
`renders/logs/generation.log` remains the source of truth; shipping is fire-and-forget and never
blocks a render. The app's `PipelineLogger` ships phone events to the rig (`POST /v1/logs`), so they
flow rig → Rybbit too.

**Critical gotcha (the "tiniest modification"):** even with an ingest key, Rybbit still runs
*user-agent* bot classification. `python-urllib`'s default UA is flagged as a bot, which routes logs
into `bot_events` (hidden from dashboards). The handler therefore sends a **browser-like
`user_agent`** so logs land in the `events` table. Keep this if you rewrite the shipper.

### View logs as a stream

Rybbit is analytics-shaped; to read logs chronologically, create a **Custom Dashboard** (SQL) with:

```sql
SELECT timestamp, event_name, JSONExtractString(properties, 'msg') AS message
FROM analytics.events
WHERE tag = 'aircheck-rig'
ORDER BY timestamp DESC
LIMIT 200
```

Errors arrive as `event_name = 'rig_error'` (properties carry `message`).

## Operations

- **Update databases / full stack manually (rare):** `cd /opt/rybbit && git pull && ./update.sh`
  (Watchtower handles the app images automatically; this is for pinned DB majors).
- **Status:** `docker ps`; `docker logs watchtower --tail 20`; `curl https://logs.relay.stare.network/api/health`.
- **Backups:** `/opt/rybbit-backups/{daily,weekly}`; backup log `/var/log/rybbit-backup.log`.
- **Reprovision:** `cloud-init.yaml` is the full first-boot script (DO "User data"). It now bakes the
  correct domain `logs.relay.stare.network`, signup-disabled, the 90-day TTL, and the disk-guard — a
  fresh droplet comes up locked-down and disk-bounded with no manual steps.

## Files

- `cloud-init.yaml` — self-sustaining first-boot (Docker + Rybbit + Watchtower + signup-off + TTL + disk-guard + backups + auto-upgrades).
- `docker-compose.watchtower.yml` — auto-update overlay (also copied to `/opt/rybbit`).
- `backup.sh` — bounded backup script (also at `/opt/rybbit-deploy/backup.sh`).
- `disk-guard.sh` — disk safety net (also at `/opt/rybbit-deploy/disk-guard.sh`).
