# Project status and continuation plan

Written so a new session (human or agent) can continue without the original conversation.
Read this file first, then `SPEC.md` (the build spec) and `docs/adr/` (decisions).
**Ready-to-paste prompts for every remaining stage are in [`docs/PROMPTS.md`](PROMPTS.md).**
Keep this file current: update the status, results log and next steps in every PR that changes them.

_Last updated: 2026-11-16: **stage 1b-2a, the Today screen** (landing page; fresh / missing with reason / stale, never an older recovery as today's; personal strain target [configurable]; HRV in % of the 30-day baseline; `GET /api/athletes/{id}/today`, ADR 0014; display only, no score or feature change); **R-12 test/CI gaps closed** (DST and timezone day-assignment tests, Vitest for the frontend, CI builds every Docker image on pull requests; no behaviour change); **R-09 sessions** (a removed login or changed password ends its sessions at once, V18, login slowdown after 5 wrong passwords, API docs need a login, ADR 0005); **stage 1b-3 verified on the coach's data** (window 2026-11-09 → 11-14, 6 of 6 days compared, page n = query n; first Garmin revision, 2026-11-09, applied); **stage 1b-3 PR 3, keeping the holdout fed** (Whoop + Garmin exports every week or two, "Keeping it fed" panel, `/holdout/feed`, README section 7); **R-10 raw revisions** (ADR 0010, V17: a Garmin day exported again replaces the half-finished version; no feature change, holdout not restarted); **stage 1b-3 PR 2, the Holdout page and decision report** (coach tab "Holdout", `GET /api/athletes/{id}/holdout/report`); **stage 1b-3 PR 1, the live holdout calculation** (`metrics.holdout`: Wilson intervals, ADR 0009 H2–H8 on synthetic tests, `GET /api/athletes/{id}/holdout`, V16 Recompute features log, ADR 0013); ADR 0009 live holdout protocol and frozen holdout targets (R-07, V15). 2026-11-15: **stage 1b-1 daily scoring** (ScoringService, REST + CLI, Recompute features rescores, V14, ADR 0012; parity test green on all three fixtures); ADR 0008 scoring contract (R-01); backup hardened and restore-tested (R-08); unfinished Whoop cycles (R-06); versioned coefficients contract, calibration 0.6.0 (R-02); parity fixtures and feature-version guard (R-03); activation time recorded and unsafe activations refused (R-04, V13). **Model m15-20261115 (f3, contract v2) is ACTIVE since 2026-11-15 19:47:54 UTC**; m13 is RETIRED at the same instant; a candidate older than the ACTIVE version (m14) can no longer be activated._

---

## 1. Where we are

| Phase (SPEC.md) | Status |
|---|---|
| 0 Foundation | **Done** (#1–#5). |
| 1a Recovery & Strain calibration | **Done: PASS on 2026-11-13** (§3). m13-20261113 activated 2026-11-13; replaced by m15-20261115 (same coefficients, contract v2), ACTIVE since 2026-11-15 19:47:54 UTC. |
| 1b Daily scoring, Today/Trends, live holdout, Garmin sync | **Next.** Stages 1b-1 … 1b-4 (§5). First the review items that block 1b-1 (`docs/REVIEW.md`): R-01 done (ADR 0008); R-08 done (backup); R-06 done; R-02 done (contract v2; m15 reproduces the PASS model); R-03 done (parity fixtures); R-04 done (activation record and guards); m15 activated 2026-11-15. **1b-1 done in code** (PR pending the coach's check on real data: every paired day scored by m15). R-07 done (ADR 0009, V15). **1b-3 in progress** (live holdout, follows ADR 0009; ADR 0013): PR 1 the calculation and API (merged #26), PR 2 the Holdout page and decision report (merged #27), R-10 raw revisions (merged #28), PR 3 the Whoop + Garmin re-import flow (merged #29). **1b-3 done and verified on the coach's data (2026-11-16)**: after new Whoop and Garmin exports, the window ran 2026-11-09 → 11-14, all 6 days compared on both sides (page n = SQL n), and 2026-11-09 was corrected by the new Garmin export (1 revision). **1b-2a Today screen done in code (2026-11-16, ADR 0014)**; the coach checks it on their phone for 5 mornings. Next: 1b-2b Trends (separate session) and 1b-4. |
| 2 Adaptive engine | After 1b. Highest product priority after Module 1 (SPEC.md). Stages 2-1 … 2-5. |
| 3 Plan builder | After 2. Stages 3-1 … 3-3. |
| 4 Extensions | Last. Stages 4-1 … 4-3. |

The spec's working agreement applies to every stage:
- restate the plan in under a page, with files, open questions and risks, then **wait for the coach's go**;
- stop at the end of each phase with a demo and a short written summary;
- ask instead of assuming;
- mark every sports-science number **[configurable]**, with a one-line rationale.

## 2. The coach's setup (important for how work is done)

- **Machine:** a MacBook with Docker Desktop. The stock `python3` is **3.9**, so `tools/inspect_exports.py` must stay 3.9-compatible; CI checks this.
- **All personal data lives only in the coach's local database.** The cloud session cannot reach it: there is no Remote Control or desktop app, and large files can't be uploaded. To diagnose problems, give the coach **read-only SQL** to paste (`docker compose exec db psql -U training -d training -c "…"`) and ask for the output. Never ask for exports or credentials in chat.
- **Athlete id 1 = the coach.** Profile: HR max 184, resting HR 48, sex M, timezone Europe/Bucharest.
- **Commands the coach runs** (in `~/claude-training-intelligence`):
  - update: `git checkout main && git pull && docker compose up --build -d`
  - after any import or profile change, or a feature-version bump: **Calibration → Recompute features** (in the UI). Since 1b-1 this also recomputes the daily scores from the ACTIVE model; also press it after activating a new model.
  - rescore only (all days, or a range): `docker compose run --rm app score --athlete 1 [--from YYYY-MM-DD] [--to YYYY-MM-DD]`
  - calibrate: `docker compose run --build --rm calibration --athlete 1`. **Keep `--build`**: `up --build` does not rebuild this on-demand service (#11).
  - import status: `docker compose exec db psql -U training -d training -c "SELECT id, status, progress, stats, error FROM import_batch ORDER BY id DESC LIMIT 1;"`
  - live holdout (stage 1b-3): every week or two, import a **Whoop export and a Garmin account export** (in the order requested), then press **Recompute features**; the **Holdout** tab's "Keeping it fed" panel shows what has arrived. **Download decision report** saves the HTML report; keep the one downloaded after the last Whoop export (2027-02-19) and a Garmin export after it (README section 7).
- The coach merges PRs themselves, usually quickly, and then reports results. One PR per agreed step works well.

## 3. Calibration results log (athlete 1)

Train 2026-01-19 → 2026-09-09; test 2026-09-10 → 2026-11-09 (the last ~20% of paired days).

| Model | Strain MAE | Recovery bands | Green↔red | What happened / fix |
|---|---|---|---|---|
| m1 | 5.03 | 55% | 0% | Whoop cycle dates were one day early: the export starts a cycle at sleep onset. Fixed in #8 (wake-day rule, V7, alignment check). |
| m2 | 2.34 | 88% | 0% | Aligned. Strain sees only workouts. |
| m5 | 2.34 | 88% | 0% | Identical result: a stale calibration image and no f2 features. Fixed in #11 (`--build`, version line). |
| m9 | 1.91 | 89% | 0% | All-day HR background load (f2). Small weight (0.11). Low-coverage days were silently dropped (n 59 → 55). |
| m12 | 2.38 on full-coverage days (2.57 overall) | 88% | 0% | Whole-day zone model (f3) won training CV by 4.8% but overfit. Led to the parsimony rule (#13). |
| **PASS** (m13) | **1.91, Spearman 0.82** (55 full-coverage days) | **88%** | **0%** | #13: zones must win CV by ≥ 10%, so "Edwards workout load + background" was selected. Strain targets revised by the coach to MAE ≤ 2.0 and Spearman ≥ 0.80 on days with ≥ 10 h of all-day HR. |
| m14 (2026-11-15) | 1.91, 0.82 (55) | 88% | 0% | R-02 re-run with `--test-from 2026-09-10 --test-to 2026-11-09`: contract v2, same recovery and strain coefficients as m13. But the features predated V12 and still held the removed 9 Nov Whoop recovery, so the test window ran to 11-09. Not to be activated. |
| **m15** (2026-11-15) | **1.91, 0.82 (55)** | **88%** | **0%** | After Recompute features: test window 2026-09-10 → 2026-11-08, same coefficients as m14 (and m13). **ACTIVE since 2026-11-15 19:47:54 UTC.** |

### Phase 1a summary
- **Recovery** agrees with Whoop's band on 88% of unseen days, with **no green-vs-red flips**. This is the score that drives training decisions.
- **Strain** ranks days like Whoop (Spearman 0.82), but individual days can be off by up to ±4–5 points. The model: strain = c + a·ln(1 + b·(Edwards workout load + k·background load)).
- **Main lessons:**
  - Verify day alignment between data sources: the report now checks it.
  - Daily life matters for strain.
  - Flexible models overfit with only ~230 training days.
  - Never let a stale image or stale features fool a comparison.
- **Note (R-06):** the last day of the test window, 2026-11-09, came from an unfinished Whoop cycle: in the stored features it had a recovery (77) but no strain. It does not change the PASS. Unfinished Whoop cycles are no longer imported (ADR 0003), and m15 is calibrated without that day.
- **Lesson (m14):** `day_features` is derived data. After a migration or import that changes raw rows, press **Recompute features** before calibrating, or the calibration reads stale features.
- **Known gaps:**
  - Low-coverage days (watch off) are lower confidence.
  - 66 Whoop-only workouts are only partly visible to us.
  - HRV only exists from 2025-09-17.
  - Some test-window evidence was used when adopting the parsimony rule, so **the live holdout (stage 1b-3) is the clean go/no-go check**. Its rules are fixed in **ADR 0009**: days from 2026-11-09 (the day after m15's test window) to the last Whoop day, pass marks frozen in `model_version.holdout_targets`, a verdict only after **90 compared recovery days**, n and Wilson intervals next to every rate, and yesterday's Whoop band as a naive baseline.

## 4. Facts learned from the real data

- **Garmin export** (2024-09 → 2026-11):
  - 1,360 activities, 1,357 of them with activity FIT files; 719 sleeps; 728 daily summaries.
  - Overnight HRV exists only **from 2025-09-17**.
  - 21,451 FIT files: 9,716 monitoring files with HR (802k samples), 503 monitoring files without HR, and about 9.9k other non-activity files.
  - `restingHeartRate` is smoothed; we use `currentDayRestingHeartRate`.
  - Activity units: duration in ms, distance and elevation in cm. `startTimeLocal` gives the local date.
  - Sleep files carry respiration and SpO2. Daily stress uses −2 to mean "no reading"; HRV values ≤ 0 are placeholders.
  - Details: ADR 0004.
- **Whoop export:**
  - 2026-01-12 → 2026-11-08: 302 cycles. **Whoop stays active until 2027-02-19**, no extension (the live holdout, ADR 0009). The last export must be taken on or after that day.
  - `Cycle start time` is **sleep onset**; a cycle belongs to the wake day (ADR 0002).
  - 466 Whoop workouts, **66 with no Garmin activity within 30 min**.
- **Not imported yet, but available:** skin temperature (`healthStatusData`), naps (`sleepData.napList`), per-activity `workoutRpe`/`workoutFeel` (143 activities, a possible seed for the check-in history in stage 2-1), UDS steps/active kcal/intensity minutes. Activity `calories` units are unverified and unused.

## 5. Plan moving forward

Each stage below has a ready-to-paste prompt in `docs/PROMPTS.md`. Every stage:
1. restates its plan and waits for the coach's go;
2. lands as one PR (or a few small ones) with tests;
3. updates this file;
4. ends with the coach-run verification steps in the prompt.

### Phase 1b: daily Module 1 (Recovery, Strain, Sleep every morning)

Standing rule while the live holdout runs (to 2027-02-19): the interim numbers on the Holdout tab are for watching, not for decisions (ADR 0009 H6: a verdict only from 90 compared recovery days; stopping early would favour a pass). No feature, `day_features` or activation change without the coach's explicit go: any of them restarts the count (H8).
| Stage | What | Done when |
|---|---|---|
| **1b-1 Daily scoring service** (done in code, 2026-11-15) | Java applies the ACTIVE model's coefficients to `day_features`. It writes `derived_day_metrics` (recovery, band, strain, sleep need and performance, per-feature contributions, `model_version`, data-quality flags: stale, low coverage, missing inputs, drift beyond the training range), with recompute for any date range via REST and a CLI. Includes a **Java/Python parity test**: same features + coefficients → same scores. | Parity test passes; the coach recomputes and sees a score per day. |
| **1b-2a Today screen** (done in code, 2026-11-16; ADR 0014) | Today (landing page): recovery dial and band, strain vs a personal target (percentiles of the athlete's own last 28 days by band [configurable]), sleep performance and need, contributions in plain words (HRV in % of the 30-day baseline), lower-confidence labels; three distinct states (fresh, missing with the stored reason, stale with "last data from <date>" and the last scored day under its own date); `GET /api/athletes/{id}/today` (AccessPolicy). | Frontend tests cover the three states and "never yesterday as today"; screenshots at 390 px light and dark (`docs/screenshots/1b-2a/`); then the coach uses it on their phone for 5 mornings and reports any morning where the screen and Whoop pointed to different training decisions. |
| **1b-2b Trends screen** | HRV, RHR, recovery, strain, sleep; acute vs chronic load; ranges from 4 weeks to 12 months; readable at phone width; i18n; dark mode. | The coach uses it on their phone for a few mornings. |
| **1b-3 Live holdout vs Whoop** (done, verified on the coach's data 2026-11-16; PRs #26–#29) | Follows **ADR 0009** (implementation choices: **ADR 0013**): a daily ours-vs-Whoop view for the ACTIVE model on the days after its test window (2026-11-09 for m15), with the pass marks frozen at activation (`holdout_targets`), excluded days counted by reason, n and Wilson intervals next to every rate, and yesterday's Whoop band as a naive baseline. The coach keeps importing new Whoop exports until 2027-02-19. | The view passes synthetic tests for every ADR 0009 rule; the decision report states pass / not met / not enough days by the rule (90 compared recovery days). |
| **1b-4 Garmin daily sync** | Implement the `garmin-sync` adapter (`garminconnect`/`garth`), with credentials encrypted at rest. A morning run (default 07:00 local, [configurable]) syncs, recomputes recent features and scores, so results are ready by 08:00; also on demand. Retry, alert, a "last sync" indicator; manual FIT upload as the fallback. **Needs the coach's consent** for storing their Garmin login. | Today's scores appear without manual imports. |

At the end of Phase 1b, stop with a demo and summary (SPEC.md Phase 1 "done when").

### Phase 2: adaptive training engine (highest priority)
| Stage | What |
|---|---|
| **2-1 Schedules & check-in** | Manual plan entry and spreadsheet (CSV/XLSX) import into `plan`/`planned_session`; key/supporting/easy demand; the athlete **Check-in** form (RPE, soreness 1–5, niggle with body location, illness, note) into `subjective_log`; optionally seed history from Garmin `workoutRpe`/`workoutFeel`. |
| **2-2 Signals** | Readiness and its 3/7-day trend; 7-day sleep debt; EWMA acute (7 d) and chronic (28 d) load, total and running-only, with ACWR; **running tolerance** (highest 4-week average weekly volume in 12 months without a niggle, decayed by time away, capped by chronic running load), with its derivation and a coach override; Foster monotony and strain. All [configurable]. |
| **2-3 Rules engine & suggestions** | A deterministic, versioned rules engine with every SPEC.md starting rule and guardrail; **one scenario test per rule** ("given signals → expect suggestion + reason"); suggestions with action, before/after, 1–3 reasons with numbers, and a confidence; an accept/edit/reject workflow in Today and Plan; an inputs snapshot logged with every decision. |
| **2-4 Backtest** | Replay the last 12 months day by day and report which suggestions the engine would have made, especially in the hard weeks the coach remembers. The coach reviews a sample. |
| **2-5 Settings screen** | Every [configurable] threshold editable, with defaults, a rationale and a reset; versioned so suggestions record which settings produced them. |

At the end of Phase 2, stop: every rule has a passing scenario test, and the backtest has been reviewed.

### Phase 3: plan builder
| Stage | What |
|---|---|
| **3-1 Methodology as data** | Session library (typed templates with structure, targets, estimated duration and strain), engine rules config (intensity distribution, progression, taper, long-run cap), race-equivalence paces (VDOT-style or Riegel) next to HR zones. |
| **3-2 Generator + property tests** | Plans for the three goal types (get fit, improve at a distance, race), ramping from current tolerance; **property tests** (no week over +10% unless overridden, taper volume always decreasing, long-run cap, lighter week every 3rd–4th week). |
| **3-3 Wizard & calendar** | Goal wizard → preview → generate → edit; week and month calendar with drag-and-drop; targets vs actuals; per-session suggestion history. The coach reviews a generated marathon plan. |

### Phase 4: extensions
| Stage | What |
|---|---|
| **4-1** | Export sessions to Garmin as structured workouts. |
| **4-2** | Capacitor phone app with push notifications for new suggestions. |
| **4-3** | Learning from logged coach decisions (a model trained on the accept/edit/reject history). |

### Backlog (cross-cutting; pick up when relevant)
- Romanian translation (`frontend/src/i18n/ro.json`).
- A golden-file check on a frozen 60-day real sample (spec), run locally by the coach, since real data can't be committed.
- Import skin temperature, naps, UDS steps/active kcal. These might later improve strain. They need new tables or source_ids (a re-imported Garmin record with new values becomes a revision row, ADR 0010).
- The strain improvement ideas parked in 1a: a smooth single-parameter intensity curve; day-level activity features.
- Per-athlete export and delete (spec privacy); TLS/`COOKIE_SECURE` guidance for exposure beyond the LAN.

## 6. How the code is organised

- `backend/` (Java 21, Spring Boot, Flyway, TimescaleDB):
  - `ingestion` (importers, `RawWriter`, import batches and progress);
  - `metrics` (`Trimp`, `Baseline`, `ActivityLoadService`, `DayFeatureService`, model versions and activation: `activated_at`/`activated_by`/`retired_at`, refused for another feature version, a contract without `schema_version` 2, a candidate older than the ACTIVE version, or one without stored pass marks or test window; activation freezes `holdout_targets`, ADR 0009);
  - `security` (`SessionGuard` re-checks the user on every request, R-09; `LoginThrottle`), `athletes`; placeholder packages `planning` and `adaptation`.
  - daily scoring (stage 1b-1, ADR 0012): `ScoringContract` (reads coefficients, refuses anything but `schema_version` 2), `DailyScorer` (ADR 0008 S1–S8, pure arithmetic, checked by `DailyScorerParityTest` on the committed fixtures), `ScoringService` (the one entry point; scores the whole grid, replaces only the requested dates of the ACTIVE model's rows, keeps other models' rows), `ScoreController` (`POST /api/athletes/{id}/scores/recompute`, `GET /api/athletes/{id}/scores`), `ScoringCli` (`score` command; the app starts without its web server and skips import housekeeping);
  - live holdout (stage 1b-3, ADR 0009 rules, ADR 0013 choices), package `metrics.holdout`: `HoldoutEvaluator` (pure arithmetic: window, one group per day and score, rates with `Wilson` intervals, MAE/Spearman as in `calibration/metrics.py`, naive baseline, verdict), `HoldoutService` (reads the version's own `derived_day_metrics`, finished `whoop_reference` cycles, the frozen `holdout_targets`; a retired version only sees Whoop rows imported before its `retired_at`), `HoldoutController` (`GET /api/athletes/{id}/holdout[?version=]`, `/holdout/versions`, `/holdout/report`, `/holdout/feed`; coach only, read-only), `DecisionReport` (the static HTML decision report, built on request, never stored). Every Recompute features run is written to `feature_recompute_log` (V16) and marked on the view;
  - Today screen (stage 1b-2a, ADR 0014), package `metrics.today`: `TodayView` (pure: picks today's row or the last scored day, the state, the strain target and the plain-words inputs; never scores), `TodayService` (reads `derived_day_metrics` and `day_features.ln_rmssd` for the HRV baseline; today in the athlete's timezone from the `Clock` bean), `TodayController` (`GET /api/athletes/{id}/today`, AccessPolicy). Display constants under `app.today` in `application.yml`;
  - Garmin revisions (R-10, ADR 0010): `RawWriter` stores a Garmin wellness or sleep record that comes again with different values as a revision row (`<id>@<batch>`); readers use the newest (`daily_wellness_merged`, `sleep_session_current`, `activity_with_cycle`, V17);
  - Migrations V1–V18 (V18 `app_user.password_changed_at`, R-09; V12 removes unfinished Whoop cycles, R-06; V13 records activation and retirement times, R-04; V14 adds `derived_day_metrics.feature_version`, R-15; V15 adds `model_version.holdout_targets` and fills it in for the ACTIVE m15, R-07; V16 adds `feature_recompute_log`, ADR 0013 D6; V17 Garmin revisions, ADR 0010). **Never edit a merged migration; add a new one.**
- `calibration/` (Python 3.12, package 0.6.0, version in `calibration/__init__.py`): `pipeline.run()` is a pure function of `day_features` (plus an optional `context` with the profile values, read by `__main__` from `activity_load`). CLI: `--test-from` / `--test-to` fix the test window. `FEATURE_VERSION` (`f3`) must match `app.metrics.feature-version` (a test checks this). The **scoring contract** for 1b-1 is **ADR 0008**: the coefficients JSON, whose shape is `calibration/contract.schema.json` (`schema_version` 2: `calibration_version`, `feature_version`, `feature_config` {hr_max, hr_rest, sex_assumed}, `strain` with `clip` [0, 21], `sleep` with `performance_clip` [0, 100], `recovery`, `bands`, and `training_ranges` {min, p01, p99, max, n} per model part and input, for drift flags), *plus* the rules that turn them into daily scores (day grid, yesterday's strain, 7-day shortfall, gaps and their named reasons, clipping, rounding, bands, low-confidence flag), each with a worked example. The **parity fixtures** for 1b-1's Java test are in `backend/src/test/resources/parity/` (`adr0008`, `load`, `zones`), produced by the reference scorer `calibration/parity.py` (R-03; ADR 0008, Consequences).
- `frontend/` (React + TypeScript): **Today** (landing page, all roles; wording helpers in `src/today.ts`), Coverage, Imports, Calibration (coach; shows the scoring result after Recompute features), Holdout (coach; the live comparison with Whoop, stage 1b-3) and Athletes (coach). Wide tables scroll inside their card (`.table-scroll`), so pages don't scroll sideways at phone width.
- `tools/inspect_exports.py`: a read-only export summary, Python 3.9+, never prints free text.
- `garmin-sync/`: the adapter boundary (stage 1b-4).
- **Feature engineering exists only in Java**, materialised in `day_features`. Python only fits. Changing a feature definition means bumping `feature_version` in both places; the coach then recomputes (ADR 0007).

## 7. Working conventions

- **Branch and PR flow:**
  - work on the session's designated branch;
  - after a PR merges, restart that branch from `origin/main`;
  - force-push may be blocked: if the remote branch isn't an ancestor of main, merge main in instead;
  - one focused PR per change, with commits explaining *why*;
  - PR bodies say what changed, how it was verified, and what the coach must do after merging.
- **Bugs:** first write a test that reproduces the failure and watch it fail, then fix it (e.g. `ProgressDeadlockIT`, `WhoopCycleDateMigrationIT`).
- **Parity fixtures** (`backend/src/test/resources/parity/`): regenerate with `cd calibration && python tests/parity_fixtures.py --write` whenever the scoring rules, the calibration code or the synthetic tables change; `tests/test_parity.py` fails while they are stale. Changing `FEATURE_VERSION` or `app.metrics.feature-version` alone also fails a test: change both together.
- **No personal data in the repo:** fixtures are synthetic.
- **Checks before pushing:**
  - backend: `cd backend && ./gradlew build` (with `-Werror`; Testcontainers needs Docker);
  - calibration, tools and garmin-sync: `ruff check . && ruff format --check . && python -m pytest -q`;
  - frontend: `npm run lint && npm run typecheck && npm test && npm run build` (`npm test` is Vitest + Testing Library; tests sit next to the code as `*.test.ts(x)`, e.g. `src/pace.test.ts`); screenshots at 390 px on synthetic data: `npm run build && npx vite preview --port 4173 &` then `node scripts/screenshots.cjs <outDir>` (fails if the page scrolls sideways);
  - Docker images: CI job `images` builds all five images of `docker-compose.yml` (`app`, `web`, `garmin-sync`, `calibration` from the `tools` profile, `backup`) on pull requests, with layer caching. Locally: `cp .env.example .env && docker compose --profile tools build`. Adding a service to the compose file means adding it to the job's matrix (the `compose` job fails until you do).
  - Dates and clocks: the DST/timezone cases live in `DayAssignmentDstIT` (Europe/Bucharest 2026-03-29 and 2026-10-25, America/New_York); add a case there whenever day-assignment code changes.
- **Backups:** `deploy/backup/backup.sh` keeps a file only after it decrypts, passes `gunzip -t` and has pg_dump's footer; it prunes only after that, writes `backups/STATUS`, and fails with a non-zero exit and a stderr message otherwise. `deploy/backup/test-backup.sh` (CI job `backup`) tests failure, the size floor `BACKUP_MIN_BYTES` (default 10240), pruning and a full restore. Locally without the image: `TEST_HOST=1 sh deploy/backup/test-backup.sh`.
- **CI:** `.github/workflows/ci.yml` (the `tools-py39` job runs in `python:3.9-slim`).

### Notes for the cloud session environment (not the coach's machine)
- The Docker daemon may need starting: `nohup dockerd >/tmp/dockerd.log 2>&1 &`.
- Image builds inside Docker can't download packages (TLS proxy). For an end-to-end check:
  - build the jar (`./gradlew bootJar`) and the frontend on the host;
  - run `timescale/timescaledb:2.17.2-pg16` on port 5433 and start the jar against it;
  - use `curl` with the CSRF cookie flow;
  - run the calibration from a host venv with `PGHOST/PGPORT/…` set.
- Python: create the venv with the system `python3.12 -m venv` plus `pip`.
- `Fixtures` (backend tests) can write synthetic export files for manual runs.
