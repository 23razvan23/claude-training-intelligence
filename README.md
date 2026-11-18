# Training Intelligence

A self-hosted training app for a running coach and the athletes he coaches. It turns Garmin data into Whoop-like Recovery, Strain and Sleep scores, builds periodized plans, and suggests day-by-day adaptations that the coach accepts or rejects. The full specification is in [SPEC.md](SPEC.md).

**Status: Phase 1a done** (Garmin-derived Recovery and Strain calibrated against Whoop: PASS). Phase 1b in progress: daily scores, and the live comparison with Whoop on unseen days (until the Whoop subscription ends on 2027-02-19). See [docs/PLAN.md](docs/PLAN.md).

## Quick start

```bash
cp .env.example .env
```

Edit `.env`: set every password and the backup passphrase.

```bash
docker compose up --build
```

Open <http://localhost:8080> and sign in with `COACH_USERNAME` / `COACH_PASSWORD` from `.env`. On first start the app creates that coach account and the coach's own athlete profile.

| Service | What it is |
|---|---|
| `web` | nginx serving the PWA; proxies `/api` to `app` |
| `app` | Spring Boot backend (Java 21); runs Flyway migrations on start |
| `db` | PostgreSQL 16 + TimescaleDB |
| `garmin-sync` | Garmin sync sidecar: signs in to Garmin for the app; keeps no token (ADR 0011) |
| `backup` | Nightly encrypted `pg_dump` into `./backups` |

API docs (OpenAPI): <http://localhost:8080/api/docs>. Raw spec: `/api/openapi`.

## Importing your data

Do this once per athlete. Pick the athlete in the top bar, then open **Imports**. Imports are idempotent, so uploading the same file twice adds nothing; the batch shows rows as "already present".

### 1. Whoop export (first import; then every week or two until Whoop ends, see section 7)
1. Request a data export from the Whoop app's settings (menu names change between app versions). Whoop emails a link to a ZIP.
2. Upload the ZIP as-is under **Whoop export**, or upload its CSV files (`physiological_cycles.csv`, `sleeps.csv`, `workouts.csv`, `journal_entries.csv`).

### 2. Garmin account export (history backfill)
1. In your Garmin account's data management page, choose **Export Your Data**. Garmin emails a link to one large ZIP; it can take a few days.
2. Upload that ZIP, unchanged, under **Garmin account export**. Activity FIT files nested inside it are read too.

### 3. FIT files (fallback)
Drag single activity `.fit` files under **FIT files**. A file that matches an already-imported activity (same start ±120 s) attaches its second-by-second data to it instead of creating a duplicate.

### 4. Check coverage
**Coverage** shows, per day, whether complete Garmin data (resting HR + overnight HRV + sleep) and complete Whoop data (recovery + strain) exist. Paired days are what Phase 1 calibrates on.

> **Before Phase 1:** the importers were written against the documented export formats, not real files ([ADR 0004](docs/adr/0004-export-format-assumptions.md)). Run the read-only inspector on your real exports and compare:
> ```bash
> python3 -I tools/inspect_exports.py my_whoop_data.zip garmin_export.zip
> ```
> It prints only counts, ranges and dates, never notes or names.

> **All-day heart rate** comes from the monitoring files inside the Garmin export. If you imported the export before this feature existed, upload the same ZIP once more. Only the missing data is added.

### 5. Calibrate (Phase 1)
1. In **Athletes**, set HR max, resting HR and sex. Strain needs them.
2. In **Calibration**, press **Recompute features**. Do this again after any import or profile change.
3. Run the calibration job. It isn't started by `docker compose up`:
   ```bash
   docker compose run --build --rm calibration --athlete 1
   ```
   Use your athlete id, shown on the Calibration screen. Keep `--build`: `docker compose up --build` doesn't rebuild this on-demand job, so without it an old version keeps running after you update.
4. The new model version appears under **Calibration**, with PASS/FAIL against the targets and its full report. When a version passes and you're happy with its report, press **Activate**: daily scores and the live comparison with Whoop will use it.

How it works: [ADR 0007](docs/adr/0007-module1-calibration.md). The targets are in `calibration/targets.json`.

### 6. Daily scores (Phase 1b)
Once a model is ACTIVE, **Recompute features** also computes the daily Recovery, Strain and Sleep scores from it. To rescore without recomputing features, for all days or a date range:
```bash
docker compose run --rm app score --athlete 1 --from 2026-11-01 --to 2026-11-15
```
`--from` and `--to` are optional. It runs next to the running app and prints a one-line summary. How the scores are computed: [ADR 0008](docs/adr/0008-scoring-contract.md); what is stored: [ADR 0012](docs/adr/0012-daily-scoring-storage.md).

### 6c. The golden check: did an update move my real scores?
While the live holdout runs, scores must be produced the same way every day. This check proves it on your own data (nothing is shared, nothing stored changes). Once, now:
```bash
mkdir -p backups/golden
docker compose run --rm app golden freeze --athlete 1
```
This snapshots 60 days (ending 2 days before your last data) as hashes in `./backups/golden/` (git-ignored; treat it like a backup, never share it). After **every update** (and before pressing anything else), run:
```bash
docker compose run --rm app golden check --athlete 1
```
- `0 differences`: nothing moved. Carry on.
- `CODE CHANGED` (exit 1) with dates and column names: the update moved your real numbers. Do not proceed; send Claude the printed lines (they contain no values).
- `INPUTS CHANGED` (exit 3): your profile, the ACTIVE model or raw rows changed, so the check cannot judge. If you expected it, run `golden freeze` again.
New Garmin or Whoop exports and syncs never trigger it. How it works and what each case means: [ADR 0017](docs/adr/0017-golden-check.md).

### 6d. Check-in history from your Garmin watch
If you entered an effort (RPE) on the watch after workouts, a Garmin export import turns each one into a check-in labelled **from Garmin** (RPE = Garmin's value ÷ 10; your own check-in for the same activity always wins). It happens by itself after every Garmin export import. The first time, upload your Garmin export again under **Imports** (nothing is duplicated), or, to run it by hand:
```bash
docker compose run --rm app garmin-seed --athlete 1
```
It prints how many check-ins it added, how many activities already had one and how many had no usable RPE; running it again adds nothing. Rules: [ADR 0020](docs/adr/0020-checkin.md) (amendment).

### 6a. The Today screen
**Today** is the landing page: the morning check on your phone. It shows today's recovery (dial in the band colour), strain so far against a personal target range, sleep performance and need, and what moved recovery, in plain words. While data comes from exports, it usually says **"No data for today yet. Last data from <date>"** and shows the last scored day under its own date: it never shows an older recovery as today's. The coach picks the athlete in the top bar; athletes see only their own. Rules: [ADR 0014](docs/adr/0014-today-screen.md).

### 6b. The Trends screen
**Trends** draws HRV, resting HR, recovery (dots in the band colour), strain, sleep and training load over 4 weeks, 3 months or 12 months. A day without data is a **gap**, never zero. Acute (7 d) and chronic (28 d) load are averages of your daily heart-rate load, not of strain; their ratio is shown as one signal, not a verdict. Overnight HRV exists only from 2025-09-17. Rules: [ADR 0016](docs/adr/0016-trends-screen.md).

### 7. Live comparison with Whoop (until 2027-02-19)
The **Holdout** tab (coach) compares our scores with Whoop's on days the active model has never seen. Its rules are fixed in [ADR 0009](docs/adr/0009-live-holdout-protocol.md). It needs new data from both sides, so repeat this every week or two:
1. Request a **Whoop** data export and a **Garmin** account export. Garmin's email can take a few days.
2. Under **Imports**, upload each ZIP unchanged as it arrives, in the order you requested them.
   - Uploading the same file twice adds nothing.
   - A Whoop export taken during the day holds today's unfinished cycle. It is skipped, with a warning, and comes in finished with the next export.
   - A Garmin day exported half-finished is replaced by the newer version from a later export ([ADR 0010](docs/adr/0010-raw-revisions.md)).
3. Under **Calibration**, press **Recompute features**.
   - With the Garmin daily sync on (section 8), keep doing the Garmin export anyway: compared days scored from a synced night wait for it.
4. Open **Holdout**. Its "Keeping it fed" panel shows the last Whoop, Garmin and scored days, and says when Recompute features is needed.

**When Whoop ends:** take the last Whoop export on 2027-02-19 and a Garmin export after it. Import both, press Recompute features, then press **Download decision report** on the Holdout tab and keep the file. It states pass / not met / not enough days by the rule.

### 8. Garmin daily sync: connecting your Garmin account (Phase 1b)
The app can sign in to Garmin Connect as you and fetch each day's data itself ([ADR 0011](docs/adr/0011-garmin-credentials.md)). You type your Garmin password **once**. The app uses it for one sign-in and keeps only the renewable access token Garmin hands back, locked with a key that lives in your `.env` file. The password is never stored or logged.

**One-time setup** (on the Mac, in `~/claude-training-intelligence`):
1. Generate two random values. Run this command twice and copy each result:
   ```bash
   openssl rand -base64 32
   ```
2. Open `.env` and fill them in: the first after `GARMIN_TOKEN_KEY=`, the second after `GARMIN_SYNC_SECRET=`. Never paste them anywhere else.
3. Restart: `docker compose up --build -d`. Without these two values the app works as before; only Garmin sync is switched off, and the Imports page says so.

**Connect:**
1. **On the Mac itself**, open `http://localhost:8080` (or your `WEB_PORT`). The app has no HTTPS yet, so from another device your password would cross the network unencrypted, once.
2. **Imports** → **Garmin connection**: type your Garmin e-mail and password and press **Connect**.
3. If Garmin sends you a code (e-mail or text message), a code field appears in the same form. Type the code within 5 minutes.

**Check, then sync:**
1. Under the Garmin card, open **Check against export**. Pick about a week your last Garmin export covers fully (not its last day, which was exported half-finished) and press **Check**. Nothing is saved. It should say the sync matches your export in everything the scores use. If it lists differences, send Claude the table and column names it shows (it never shows values).
   The check also lists **where Garmin keeps the precise sleep values** (field names and how many nights match, never values). Send that table to Claude too.
2. Press **Sync now**. It fetches today and the 2 days before, imports them like an export, and then recomputes features and scores by itself. The card shows the last sync and its result. Running it twice adds nothing the second time. If Garmin is unavailable, it tries again after 10 and 30 minutes.
3. Each sync appears under **Import history** as "Garmin sync".

**The morning sync.** Once connected, the app syncs by itself every morning at **07:00** (Bucharest time, or the athlete's timezone): it fetches today and the 2 days before, then recomputes features and scores. If the Mac was asleep at 07:00, the sync starts within a minute of the Mac waking up, the same day, once. Today and Imports show **Last sync**; if today's data is missing, Today says why: the sync failed (and whether it tries again), Garmin needs you to connect again, or Garmin had no data for today yet (open the Garmin Connect app so the watch uploads the night, then press **Sync now**).

**Wake the Mac at 06:55 every day** so the 07:00 sync runs before you check (one Terminal command; it asks for your Mac password):
```bash
sudo pmset repeat wakeorpoweron MTWRFSU 06:55:00
```
Check it with `pmset -g sched`; remove it with `sudo pmset repeat cancel`. Docker Desktop must be running (set it to start when you log in). A scheduled wake is most reliable with the Mac plugged in. If the Mac doesn't wake, nothing breaks: the sync runs when you open it.

**Synced mornings are approximate for breathing rate.** Garmin's live data rounds the night's breathing rate, so on a synced morning recovery may read a few points high, and Today says so. Your next Garmin export brings the precise value: import it (with each Whoop export, section 7), press Recompute features, and those days are rescored. A synced night never replaces one already imported from an export. On the **Holdout** tab, a compared day scored from a synced night does not count toward the pass marks until then: the page says "N compared days wait for a Garmin export" ([ADR 0015 D13](docs/adr/0015-garmin-daily-sync.md)).

**If Garmin needs you to connect again** (it stopped accepting the token, or you changed `GARMIN_TOKEN_KEY`), the card says so and shows the form again. Sync pauses until you do; imports keep working.

**Disconnect** deletes the stored token from the app. Garmin cannot be told to cancel that token from here. To be thorough, also sign out of other devices in your Garmin account's security settings, or change your Garmin password.

**Keep `.env` away from backup copies.** The nightly backups contain only the locked token, useless without `GARMIN_TOKEN_KEY`. When you copy `backups/` to another disk or cloud drive, do not copy `.env` with it. Keep a copy of `.env` somewhere else, as with the backup passphrase. If `.env` is lost, nothing else is: put new values in and connect again.

**Network:** the `garmin-sync` service needs to reach Garmin over the internet (outbound HTTPS) from this Mac. Nothing on your network can reach it: it has no published port, and the app talks to it with `GARMIN_SYNC_SECRET`.

**Unofficial API:** Garmin has no official API for this. If Garmin changes its sign-in, sync stops with a clear message until the app is updated. Garmin account exports and FIT uploads (sections 2 and 3) always remain the fallback.

Personal exports never belong in git: `.gitignore` excludes `data/`, `*.zip`, `*.fit` and `.env`.

## Development

Requires Java 21, Node 22, Python 3.12 and Docker (for Testcontainers).

```bash
cd backend && ./gradlew build
```

This compiles with `-Werror` and runs unit and integration tests against a real TimescaleDB in Docker.

```bash
cd frontend && npm ci && npm run dev
```

The Vite dev server on :5173 proxies `/api` to :8081. To run the backend locally against a database:

```bash
docker run -d --name ti-db -p 5432:5432 -e POSTGRES_DB=training -e POSTGRES_USER=training -e POSTGRES_PASSWORD=training timescale/timescaledb:2.17.2-pg16
```

```bash
cd backend && COACH_USERNAME=coach COACH_PASSWORD=dev-password-1 ./gradlew bootRun
```

Python parts (`garmin-sync/`, `calibration/`, `tools/`): run `ruff check . && ruff format --check . && python -m pytest -q` in each.

CI (`.github/workflows/ci.yml`) runs the backend build and tests, the frontend lint, typecheck and build, the Python lint and tests, and a Compose config check on every push.

### Layout

```
backend/        Spring Boot modular monolith
  athletes/     profiles, zones
  security/     auth, users, roles, access policy
  ingestion/    Whoop/Garmin/FIT importers, raw writer, coverage report
  metrics/      Module 1 (Phase 1)    planning/  Module 2 (Phase 3)    adaptation/  Module 3 (Phase 2)
  resources/db/migration/   Flyway schema (V1 core, V2 raw, V3 derived + planning)
frontend/       React + TypeScript PWA (i18n keys, dark mode)
garmin-sync/    Python sidecar: Garmin sign-in (garminconnect, pinned) and adapter boundary
calibration/    Python calibration job (Phase 1)
tools/          inspect_exports.py
deploy/backup/  nightly encrypted backup
docs/adr/       Architecture Decision Records
```

## Backups

The `backup` service writes `backups/training-<UTC timestamp>.sql.gz.enc` nightly at 03:00 UTC. It keeps the newest 14; set `BACKUP_HOUR` and `BACKUP_KEEP` to change that. To back up now:

```bash
docker compose run --rm backup now
```

To restore, into an empty database. TimescaleDB needs its pre/post restore hooks:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in backups/training-XXXX.sql.gz.enc -out restore.sql.gz
```

```bash
docker compose exec -T db psql -U training -d training -c "SELECT timescaledb_pre_restore();"
```

```bash
gunzip -c restore.sql.gz | docker compose exec -T db psql -U training -d training
```

```bash
docker compose exec -T db psql -U training -d training -c "SELECT timescaledb_post_restore();"
```

Keep the passphrase somewhere other than this server: without it the backups cannot be read.

Copy `backups/` to another disk now and then (the files are encrypted; keep the passphrase elsewhere, not next to them). A backup is only kept if it was checked after writing; `backups/STATUS` shows the last success (time, size). If a run fails, the reason is in `docker compose logs backup`, and older backups are left alone. `BACKUP_MIN_BYTES` (default 10240) is the smallest size accepted.

## Privacy

This is health data. Self-host only. Uploaded export files are deleted once imported. Logs never contain payloads or credentials. Deleting an athlete (coach only) removes all their data. Put the app behind TLS and set `COOKIE_SECURE=true` before exposing it beyond your LAN.

## Architecture decisions

**Continuing the work?** Start with [docs/PLAN.md](docs/PLAN.md): it covers status, results so far, facts about the real data, next steps and conventions. Ready-to-paste prompts for each remaining stage are in [docs/PROMPTS.md](docs/PROMPTS.md).

See [docs/adr/](docs/adr/): modular monolith (0001), what a "day" is (0002), raw data and dedup (0003), export format assumptions (0004), auth (0005), import handling (0006), Module 1 calibration (0007), scoring contract (0008), live holdout (0009, 0013), raw revisions (0010), Garmin credentials (0011), daily scoring storage (0012), Today screen (0014), Garmin daily sync (0015), Trends screen (0016), golden check (0017).
