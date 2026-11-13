# Project status and continuation plan

Written so a new session (human or agent) can continue without the original conversation.
Read this file first, then `SPEC.md` (the build spec) and `docs/adr/` (decisions). Keep this
file current: update the status, results log and next steps in every PR that changes them.

_Last updated: 2026-11-13, with the strain parsimony rule, revised strain targets and model activation (PR after #12)._

---

## 1. Where we are

| Phase (SPEC.md) | Status |
|---|---|
| 0 Foundation | **Done** (#1–#5): schema, auth, athletes, idempotent Whoop/Garmin/FIT import, coverage report, compose, CI, backups. |
| 1a Recovery & Strain calibration | **Nearly done.** Recovery passes. Strain targets revised by the coach (≤ 2.0 MAE, Spearman ≥ 0.80). Remaining: rerun, then the coach activates a passing version (§5.1). |
| 1b Daily scoring, Today/Trends, Garmin sync | Not started (§5.2). |
| 2 Adaptive engine | Not started. Highest product priority after Module 1 (SPEC.md). |
| 3 Plan builder | Not started. |
| 4 Extensions | Not started. |

The spec's working agreement still applies. For each phase, restate the plan in under a page, wait for the coach's go, stop at the end of the phase for review, ask instead of assuming, and mark every sports-science number **[configurable]**.

## 2. The coach's setup (important for how work is done)

- **Machine:** a MacBook with Docker Desktop. The stock `python3` is **3.9**, so `tools/inspect_exports.py` must stay 3.9-compatible; CI checks this.
- **All personal data lives only in the coach's local database.** The cloud session cannot reach it: there is no Remote Control or desktop app, and large files can't be uploaded to the web session. To diagnose problems, give the coach **read-only SQL** to paste (`docker compose exec db psql -U training -d training -c "…"`) and ask for the output. Never ask for exports or credentials in chat.
- **Athlete id 1 = the coach.** Profile: HR max 184, resting HR 48, sex M, timezone Europe/Bucharest.
- **Commands the coach runs** (in `~/claude-training-intelligence`):
  - update: `git checkout main && git pull && docker compose up --build -d`
  - after any import or profile change: **Calibration → Recompute features** (in the UI)
  - calibrate: `docker compose run --build --rm calibration --athlete 1`. **Keep `--build`**: `up --build` does not rebuild this on-demand service, and a stale image silently used old code once (#11). The job's first line prints `calibration <version> · feature set <fN>`.
  - import status: `docker compose exec db psql -U training -d training -c "SELECT id, status, progress, stats, error FROM import_batch ORDER BY id DESC LIMIT 1;"`
- The coach merges PRs themselves, usually quickly, and then reports results.

## 3. Calibration results log (athlete 1)

Train 2026-01-19 → 2026-09-09; test 2026-09-10 → 2026-11-09 (the last ~20% of paired days).
Targets (`calibration/targets.json`): recovery band agreement ≥ 70%, green-vs-red ≤ 3%. Strain: originally MAE ≤ 1.5; **since 2026-11-13, MAE ≤ 2.0 and Spearman ≥ 0.80, on days with ≥ 10 h of all-day HR** (the coach's decision, ADR 0007 item 6).

| Model | Strain MAE | Recovery bands | Green↔red | What happened / fix |
|---|---|---|---|---|
| m1 | 5.03 | 55% | 0% | Whoop cycle dates were one day early: the export starts a cycle at sleep onset. Fixed in #8 (wake-day rule, migration V7, alignment check). |
| m2 | 2.34 | 88% | 0% | Aligned. Strain sees only workouts. |
| m5 | 2.34 | 88% | 0% | Identical result: a stale calibration image and no f2 features. Fixed in #11 (`--build`, version line). |
| m9 | 1.91 | 89% | 0% | All-day HR background load (f2). Background weight only 0.11, and the CV gain was tiny (1.865 → 1.862). Most of the test gain came from dropping 4 low-coverage days (n 59 → 55). Spearman 0.82, bias +0.64, LoA −4.3…+5.6. |
| m12 | 2.57 (full-coverage days 2.38, n=55; low-coverage 4.64, n=5) | 88% | 0% | Whole-day HR zone model (f3) won training CV by only 4.8% (1.772 vs 1.862), was worse on the test days, and its weights collapsed into a step: overfitting. |
| next | ? | ? | ? | Parsimony rule (zones must win CV by ≥ 10%) reselects workout + background load (m9-style: 1.91, Spearman 0.82 on full-coverage days). Expected to pass the revised targets. |

**Recovery is solid.** For strain, the coach chose to revise the target and move on to the live holdout rather than run another modelling round. Options left for later, if wanted: a smooth single-parameter intensity curve instead of 6 zone weights, or day-level steps and active kcal (not imported).

## 4. Facts learned from the real data

- **Garmin export** (2024-09 → 2026-11):
  - 1,360 activities, 1,357 of them with activity FIT files; 719 sleeps; 728 daily summaries.
  - Overnight HRV (`healthStatusData`) exists only **from 2025-09-17**.
  - 21,451 FIT files: 9,716 monitoring files with HR (802k samples), 503 monitoring files without HR, and about 9.9k other non-activity files.
  - `restingHeartRate` is smoothed; `currentDayRestingHeartRate` is the day's own value and is what we use.
  - Activity units: duration in ms, distance and elevation in cm. `startTimeLocal` gives the local date.
  - Sleep files carry respiration and SpO2. Daily stress uses −2 to mean "no reading"; HRV metrics use values ≤ 0 as placeholders.
  - Details: ADR 0004.
- **Whoop export:**
  - 2026-01-12 → 2026-11-08: 302 cycles, 294 complete. **Whoop stays active until 2027-02**, which gives a live holdout (§5.3).
  - `Cycle start time` is **sleep onset**, the evening before; a cycle belongs to the wake day (ADR 0002).
  - 466 Whoop workouts, of which **66 have no Garmin activity within 30 min** (strength sessions and walks not started on the watch).
- **Not imported yet, but available:** skin temperature (`healthStatusData` metrics), naps (`sleepData.napList`), per-activity `workoutRpe`/`workoutFeel` (143 activities, a possible seed for the check-in history), UDS steps/active kcal/intensity minutes. Activity `calories` units are unverified and unused.

## 5. Next steps, in order

### 5.1 Finish Phase 1a
1. The coach merges this PR, updates, and reruns the calibration (no Recompute needed; features are still f3): `docker compose run --build --rm calibration --athlete 1`. The first line should say `calibration 0.5.0 · feature set f3`.
2. Expected: the strain section says "Selected: … + background", strain passes the revised targets, and the result is PASS. If not, look at the report's strain section and "largest strain misses" table before changing anything.
3. The coach reviews the report, then presses **Activate** on that version (Calibration tab). This is the frozen model for 1b and the live holdout.
4. Stop for review: Phase 1a is complete. Write the phase summary (results, decisions, known gaps) in this file.

### 5.2 Phase 1b (restate the plan and get a go first)
- **Daily scoring in Java:**
  - read the ACTIVE `model_version.coefficients`. The contract is in ADR 0007 and `calibration/pipeline.py::_strain_coefficients`, and covers both strain forms, recovery terms with center/scale, sleep, and bands;
  - compute from `day_features` and write `derived_day_metrics`, tagged with `model_version`;
  - store per-feature contributions for explanations;
  - allow recomputing any date range (the spec wants a CLI; today only REST exists).
- **Today and Trends screens** (SPEC.md UI table), a drift warning when inputs leave the training range, and a stale-data banner ("never compute today's recovery from yesterday's data without saying so").
- **Garmin daily sync:**
  - implement `garmin-sync/garmin_sync/adapter.py` with `garminconnect`/`garth`;
  - credentials stored encrypted at rest;
  - scheduled each morning so scores are ready by 08:00 local, plus on demand;
  - retry and alert on failure;
  - manual FIT upload stays as the fallback.
  This needs the coach's consent for storing their Garmin login, and network access in their setup.
- **Live holdout (§5.3)** UI.

### 5.3 Live holdout until 2027-02
After freezing, compare the frozen model's daily scores with Whoop's every day until the subscription ends: a small report or chart of ours vs Whoop. This is the strongest go/no-go evidence for cancelling Whoop (ADR 0007).

### 5.4 Later phases
Phase 2 (adaptive engine, rules engine, backtest over 12 months), Phase 3 (plan builder) and Phase 4 are as written in SPEC.md. Also open from the spec's engineering standards:
- a **Settings screen** for all [configurable] thresholds;
- a **golden-file test on a frozen 60-day real sample**. It can't be committed (personal data); consider a coach-run local golden check;
- a Romanian translation (`frontend/src/i18n/ro.json`);
- the athlete **Check-in** form (Phase 2).

## 6. How the code is organised

- `backend/` (Java 21, Spring Boot, Flyway, TimescaleDB):
  - `ingestion` (importers, `RawWriter`, import batches and progress);
  - `metrics` (`Trimp`, `Baseline`, `ActivityLoadService`, `DayFeatureService`, model versions);
  - `security`, `athletes`.
  - Migrations V1–V10. **Never edit a merged migration; add a new one.**
- `calibration/` (Python 3.12): `pipeline.run()` is a pure function of the `day_features` table, with `strain`, `sleep`, `recovery`, `metrics` and `report` modules. `FEATURE_VERSION` in `__main__.py` must match `app.metrics.feature-version`.
- `frontend/` (React + TypeScript): screens for Coverage, Imports, Calibration (coach) and Athletes (coach).
- `tools/inspect_exports.py`: a read-only export summary, Python 3.9+, that never prints free text.
- `garmin-sync/`: the adapter boundary; Phase 1b work.
- **Feature engineering exists only in Java**, materialised in `day_features`. Python only fits. Changing a feature definition means bumping `feature_version` in both places, after which the coach recomputes (ADR 0007).

## 7. Working conventions

- **Branch and PR flow:**
  - work on the session's designated branch;
  - after a PR merges, restart that branch from `origin/main` before new work;
  - force-push may be blocked: if the remote branch isn't an ancestor of main, merge main in instead of rewriting;
  - one focused PR per change, with commits explaining *why*;
  - PR bodies say what changed, how it was verified, and what the coach must do after merging;
  - don't open PRs unless asked or clearly part of the agreed workflow (the coach has been asking for one per step).
- **Bugs:** first write a test that reproduces the failure and watch it fail, then fix it (e.g. `ProgressDeadlockIT`, `WhoopCycleDateMigrationIT`).
- **No personal data in the repo:** fixtures are synthetic, and `.gitignore` covers exports and `.env`.
- **Checks before pushing:**
  - backend: `cd backend && ./gradlew build` (with `-Werror`; Testcontainers needs Docker);
  - calibration, tools and garmin-sync: `ruff check . && ruff format --check . && python -m pytest -q`;
  - frontend: `npm run lint && npm run typecheck && npm run build`.
- **CI:** `.github/workflows/ci.yml`. The `tools-py39` job runs in a `python:3.9-slim` container, because `ubuntu-latest` has no Python 3.9.

### Notes for the cloud session environment (not the coach's machine)
- The Docker daemon may need starting: `nohup dockerd >/tmp/dockerd.log 2>&1 &`.
- Image builds inside Docker can't download Maven, npm or apk packages (TLS proxy). For an end-to-end check, build the jar (`./gradlew bootJar`) and the frontend on the host. Then run a `timescale/timescaledb:2.17.2-pg16` container on port 5433, start the jar against it, use `curl` with the CSRF cookie flow, and run the calibration from a host venv with `PGHOST/PGPORT/…` set.
- Python: create the venv with the system `python3.12 -m venv` plus `pip`. `uv pip install` hung here once.
- `Fixtures` (backend tests) can write synthetic export files for manual runs.
