# Training Intelligence

A self-hosted training app for a running coach and the athletes he coaches. It turns Garmin data into Whoop-like Recovery, Strain and Sleep scores, builds periodized plans, and suggests day-by-day adaptations that the coach accepts or rejects. The full specification is in [SPEC.md](SPEC.md).

**Status: Phase 0 (Foundation).** Includes the repo, Docker Compose, CI, two-role auth, athlete profiles, the canonical schema, idempotent Whoop and Garmin imports, and a data-coverage report. Scores, plans and suggestions come in Phases 1–3.

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
| `garmin-sync` | Garmin sync sidecar (health endpoint only until Phase 1) |
| `backup` | Nightly encrypted `pg_dump` into `./backups` |

API docs (OpenAPI): <http://localhost:8080/api/docs>. Raw spec: `/api/openapi`.

## Importing your data

Do this once per athlete. Pick the athlete in the top bar, then open **Imports**. Imports are idempotent, so uploading the same file twice adds nothing; the batch shows rows as "already present".

### 1. Whoop export (one time, before cancelling Whoop)
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
   docker compose run --rm calibration --athlete 1
   ```
   Use your athlete id, shown on the Calibration screen.
4. The new model version appears under **Calibration**, with PASS/FAIL against the targets and its full report.

How it works: [ADR 0007](docs/adr/0007-module1-calibration.md). The targets are in `calibration/targets.json`.

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
garmin-sync/    Python sidecar: Garmin adapter boundary
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

## Privacy

This is health data. Self-host only. Uploaded export files are deleted once imported. Logs never contain payloads or credentials. Deleting an athlete (coach only) removes all their data. Put the app behind TLS and set `COOKIE_SECURE=true` before exposing it beyond your LAN.

## Architecture decisions

See [docs/adr/](docs/adr/): modular monolith (0001), what a "day" is (0002), raw data and dedup (0003), export format assumptions (0004), auth (0005), import handling (0006), Module 1 calibration (0007).
