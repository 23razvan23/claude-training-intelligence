# Project status and continuation plan

Written so a new session (human or agent) can continue without the original conversation.
Read this file first, then `SPEC.md` (the build spec) and `docs/adr/` (decisions).
**Ready-to-paste prompts for every remaining stage are in [`docs/PROMPTS.md`](PROMPTS.md).**
Keep this file current: update the status, results log and next steps in every PR that changes them.

_Last updated: 2026-11-19. **Tolerance fix** (ADR 0021 amendment): the decay now counts only the current break (two normal weeks in a row end it), signals version s3; the coach's tolerance goes from 12.4 to about 37.6 km/week. Next: the coach checks the new number (and decides whether the chronic cap should leave room above 1.0), then 2-3. **2-2 done in code** (ADR 0021): PR 1 settings store + load signals (#48); PR 2 readiness, sleep debt, check-in flags, session demand, running tolerance with override (V25), Trends and Athletes screens (#51). **2-1b (athlete check-in) done in code** (ADR 0020, V23); Garmin RPE seeding is built too (ADR 0020 amendment, V24 `activity_effort`). **Phase 1 complete** (1a, 1b-1 … 1b-4), pending the coach's demo on the Mac. Review items R-01 … R-15 all done (R-05: no fix now). Stage 2-0 (Phase 2 schema) done in code. Stage 2-1 is split: **2-1a** (schedules: plan entry, import, Plan screen) and **2-1b** (check-in). Latest: 2-1a PR 2, spreadsheet (CSV/XLSX) import of a plan (ADR 0019 import note); with it **stage 2-1 is complete, including the Garmin RPE seed** (ADR 0020 amendment). Before it: 2-1b check-in and 2-1a PR 1, plan entry and the Plan screen. Full history: Change log at the end of this file._

---

## 1. Where we are

| Phase (SPEC.md) | Status |
|---|---|
| 0 Foundation | **Done** (#1–#5). |
| 1a Recovery & Strain calibration | **Done: PASS on 2026-11-13** (§3). ACTIVE model: m15-20261115 (contract v2) since 2026-11-15 19:47:54 UTC; it replaced m13-20261113. |
| 1b Daily scoring, Today/Trends, live holdout, Garmin sync | **Done (2026-11-17): Phase 1 complete**, pending the coach's demo on the Mac (summary below §3). Stages 1b-1 … 1b-4 (§5); status of each below the table. |
| 2 Adaptive engine | After 1b (highest product priority after Module 1, SPEC.md). Stages 2-0 … 2-5. **2-0 done in code** (R-13, 2026-11-17, ADR 0018, V22). **2-1a** done in code: PR 1 plan entry + Plan screen, PR 2 spreadsheet import (ADR 0019). 2-1b (check-in) done (ADR 0020). Only the Garmin RPE seed of 2-1 is open. **2-2** done in code in two PRs (ADR 0021; #48 and PR 2, V25); spot check found the tolerance decay counting every easy week since the peak; fixed 2026-11-19 (ADR 0021 amendment, signals s3). |
| 3 Plan builder | After 2. Stages 3-1 … 3-3. |
| 4 Extensions | Last. Stages 4-1 … 4-3. |

Where Phase 1b stands (as of 2026-11-17):
- **Review items first.** All of R-01 … R-15 are done (PR numbers in `docs/REVIEW.md`); R-05 needs no fix now.
- **1b-1 daily scoring:** done (merged #25). Parity test green on the committed fixtures (R-03).
- **1b-2a Today screen** (ADR 0014) and **1b-2b Trends screen** (ADR 0016): done in code, 2026-11-16. The coach checks them on their phone for a few mornings.
- **1b-3 live holdout** (ADR 0009 rules, ADR 0013 choices): done and verified on the coach's data on 2026-11-16 (PRs #26–#29, R-10 #28). Window 2026-11-09 → 11-14, 6 of 6 days compared, page n = SQL n; 2026-11-09 corrected by the new Garmin export (1 revision). It keeps running to 2027-02-19.
- **1b-4 Garmin daily sync** (ADR 0011 consent given 2026-11-16, ADR 0015), three PRs, all merged:
  - PR 1, connection: #36; the coach connected.
  - PR 2, fetch, import, Sync now, Check against export: #37; the first sync found rounded sleep averages, handled by ADR 0015 D11 (fixes #38–#40).
  - PR 3, morning run at 07:00, last-sync indicator, Today banner and the holdout guard for synced nights: #44 (ADR 0015 D9, D13).
- **Phase 1 is complete**, pending the coach's demo on the Mac.

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
  - **golden check (R-14, ADR 0017):** once, `mkdir -p backups/golden && docker compose run --rm app golden freeze --athlete 1`. Then **after every update** (any update that touches `metrics/` or `ingestion/`; simplest: every update), before anything else: `docker compose run --rm app golden check --athlete 1`. Expect `0 differences`. `CODE CHANGED` (exit 1) = the update moved real numbers: stop and report the printed dates/columns. `INPUTS CHANGED` (exit 3) = profile, ACTIVE model or raw rows changed: if expected, run `golden freeze` again. New exports and syncs never trigger it. Optional: build with `GIT_COMMIT=$(git rev-parse --short HEAD) docker compose up --build -d` so the file records the commit.
  - recompute the adaptive engine's signals (stage 2-2; also done automatically after Recompute features and the morning sync): `docker compose run --rm app signals --athlete 1`
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

### Phase 1 summary (2026-11-17)
- **Built:** imports (Whoop, Garmin export, FIT, Garmin daily sync) into append-only raw tables with revisions; one feature pipeline in Java; the Python calibration with a versioned scoring contract; the daily scorer (Java, parity-tested against Python); Today, Trends, Calibration and Holdout screens; the live holdout under fixed rules (ADR 0009) with a downloadable decision report; the Garmin sync (encrypted token, MFA, Sync now, Check against export, retries, a 07:00 morning run with catch-up after sleep); encrypted, restore-tested backups; the local golden check.
- **Results:** 1a PASS on unseen days: recovery band agreement 88%, 0 green-vs-red flips, strain MAE 1.91 / Spearman 0.82 on full-coverage days (m15 ACTIVE). The live holdout runs 2026-11-09 → 2027-02-19; verdict from 90 compared recovery days. A sync on the coach's Mac produced a morning recovery without a manual import.
- **Known limitations:** synced nights use Garmin's live breathing rate (about 3 recovery points high on average, up to ~6; marked on Today, held out of the holdout until an export arrives); strain on low-coverage days is lower confidence and single days can be off by 4–5 points; 66 Whoop-only workouts; HRV only from 2025-09-17; the Garmin API is unofficial and can break (exports remain the fallback); the morning run needs the Mac awake and Docker Desktop running; no HTTPS yet (Connect from the Mac itself); the Romanian translation is empty.

## 4. Facts learned from the real data

- **Garmin live data (sync, 2026-11-17):** the sleep summary (`dailySleepDTO`) gives breathing rate, SpO2 and sleep stress as whole numbers; the export keeps decimals (e.g. 12.4 vs 12.0), and no live field or list average reproduces them (closest: 0.45–0.52 breaths/min and 0.32 % SpO2 off on average). Everything else checked matched the export exactly on 6 nights (ADR 0015 D11, D12).
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
- **Not imported yet, but available:** skin temperature (`healthStatusData`), naps (`sleepData.napList`), per-activity `workoutRpe`/`workoutFeel` (143 activities; `workoutRpe` now seeds the check-in history and both fields are stored in `activity_effort` since V24, ADR 0020 amendment), UDS steps/active kcal/intensity minutes. Activity `calories` units are unverified and unused.

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
| **1b-2b Trends screen** (done in code, 2026-11-16; ADR 0016) | HRV, RHR, recovery (band colours), strain (low-coverage days hollow), sleep (performance; duration vs need); acute (7 d) vs chronic (28 d) EWMA on the Edwards daily load, not on strain, ratio shown as one signal [configurable]; ranges 4 weeks, 3 months, 12 months; gaps are gaps; HRV start noted; `GET /api/athletes/{id}/trends` (AccessPolicy); phone tabs wrap; plain-SVG charts, no new dependency. | Frontend tests cover range switching, gaps, low-coverage markers, the HRV note and the EWMA; screenshots in `docs/screenshots/1b-2b/`; then the coach uses it on their phone for a few mornings. |
| **1b-3 Live holdout vs Whoop** (done, verified on the coach's data 2026-11-16; PRs #26–#29) | Follows **ADR 0009** (implementation choices: **ADR 0013**): a daily ours-vs-Whoop view for the ACTIVE model on the days after its test window (2026-11-09 for m15), with the pass marks frozen at activation (`holdout_targets`), excluded days counted by reason, n and Wilson intervals next to every rate, and yesterday's Whoop band as a naive baseline. The coach keeps importing new Whoop exports until 2027-02-19. | The view passes synthetic tests for every ADR 0009 rule; the decision report states pass / not met / not enough days by the rule (90 compared recovery days). |
| **1b-4 Garmin daily sync** (done in code 2026-11-17, ADR 0015: PR 1 connection #36, PR 2 fetch/import #37, sleep fixes #38–#40, PR 3 morning run) | Implement the `garmin-sync` adapter (`garminconnect`/`garth`). Credentials follow **ADR 0011** (tokens only, AES-256-GCM in the app, `garmin_credential` table added in this stage, stateless sidecar with a shared secret); a re-fetched day is a revision (**ADR 0010**); each morning also re-fetches the previous 2 days [configurable]. A morning run (default 07:00 local, [configurable]) syncs, recomputes recent features and scores, so results are ready by 08:00; also on demand. Retry, alert, a "last sync" indicator; manual FIT upload as the fallback. Consent given 2026-11-16 (ADR 0011 D1). | Today's scores appear without manual imports; a second sync of the same morning changes no score; a later sync of the same day updates inputs and score and keeps the earlier inputs. |

At the end of Phase 1b, stop with a demo and summary (SPEC.md Phase 1 "done when").

### Phase 2: adaptive training engine (highest priority)

Standing rule while the live holdout runs: **do not add or change `day_features` columns** (any change means a new feature version, a new calibration and a restarted holdout). Phase 2 inputs are computed in the `adaptation` module from `activity_with_cycle`, `activity_load` and `derived_day_metrics`, and stored in `day_signals` (ADR 0018 D8).
| Stage | What |
|---|---|
| **2-0 Phase 2 schema corrections** (R-13; done in code 2026-11-17, ADR 0018, V22) | `is_key` dropped (`demand` only); suggestion counterpart session, settings/signals version, engine run date, `dedup_key` (unique while PENDING); `changed_by_suggestion_id`, `replaces_session_id`, append-only `planned_session_revision`; `settings_version`, `day_signals`; check-in `injury`, `niggle_resolved`, view `subjective_current` ("latest wins" per day and session / activity / whole day). Schema only. |
| **2-1 Schedules & check-in** (split: **2-1a** schedules, done in code 2026-11-18, ADR 0019: PR 1 plan entry + Plan screen, PR 2 spreadsheet (CSV/XLSX) import with a preview before anything is saved. **2-1b** check-in done in code 2026-11-18, ADR 0020, V23: Check-in tab and a "Check in" button on Today; append-only by trigger; the coach can log on an athlete's behalf and sees everyone's recent check-ins; the Garmin `workoutRpe` seed is built too: ADR 0020 amendment, V24 `activity_effort`, `garmin-seed` command and an automatic seed after each Garmin export import; see the change log) | Manual plan entry and spreadsheet (CSV/XLSX) import into `plan`/`planned_session`; key/supporting/easy `demand` (the only key marker); a `planned_session_revision` row for every change (prefer SKIPPED/REPLACED over delete); the athlete **Check-in** form (RPE, soreness 1–5, niggle with body location, niggle resolved, injury, illness, note) into `subjective_log`, read through `subjective_current`; seed history from Garmin `workoutRpe` (done, ADR 0020 amendment; `workoutFeel` is stored but not seeded). |
| **2-2 Signals and settings store** (split, ADR 0021: **PR 1** settings store, default demand per type moved into it, daily load, acute/chronic/ACWR total and running, Foster, running km/min 7 and 21 days, `day_signals`, `signals` command, done in code 2026-11-18, no migration; **PR 2** readiness, sleep debt, check-in flags, session demand, tolerance + override (V25; V24 is `activity_effort`, ADR 0020 amendment), Athletes and Trends screens, done in code 2026-11-18; signals version s2) | The versioned settings store (`settings_version`, append-only; the defaults are the first version; ADR 0018 D2). Readiness and its 3/7-day trend; 7-day sleep debt; EWMA acute (7 d) and chronic (28 d) load, total and running-only, with ACWR; **running tolerance** (highest 4-week average weekly volume in 12 months without a niggle or injury, decayed by time away, capped by chronic running load), with its derivation and a coach override; Foster monotony and strain. **ACWR and Foster use a daily load in TRIMP units (Edwards by default, as Trends does; Banister as a setting), not the 0–21 strain score**, which is a log scale and compresses hard days (ADR 0018 D9). Stored in `day_signals` with `signals_version` and `settings_version`. All [configurable]. |
| **2-3 Rules engine & suggestions** | A deterministic, versioned rules engine with every SPEC.md starting rule and guardrail; **one scenario test per rule** ("given signals → expect suggestion + reason"); suggestions with action, before/after, 1–3 reasons with numbers, and a confidence; every suggestion records `rules_version`, `settings_version`, `signals_version`, `engine_run_date` and the inputs snapshot; re-runs rely on `dedup_key` and expire outdated pending suggestions (ADR 0018 D3); an accept/edit/reject workflow in Today and Plan; accepting sets `changed_by_suggestion_id` and writes a session revision. |
| **2-4 Backtest** | Replay the last 12 months day by day and report which suggestions the engine would have made, especially in the hard weeks the coach remembers. The coach reviews a sample. Done when: the replay uses, for each day, only rows whose data existed by that morning (a no-look-ahead test included). |
| **2-5 Settings screen (UI only)** | Every [configurable] threshold editable, with defaults, a rationale and a reset; saving adds a new `settings_version` (the store itself comes in 2-2). |

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
- Athletes ask for a session to be moved or changed when a day is missed (coach, 2026-11-18; ADR 0019 D6). Later stage, after 2-3's suggestion workflow.
- Import skin temperature, naps, UDS steps/active kcal. These might later improve strain. They need new tables or source_ids (a re-imported Garmin record with new values becomes a revision row, ADR 0010).
- The strain improvement ideas parked in 1a: a smooth single-parameter intensity curve; day-level activity features.
- Per-athlete export and delete (spec privacy); TLS/`COOKIE_SECURE` guidance for exposure beyond the LAN.
- Confirm the first month of the Garmin export (PLAN §4 says 2024-09, ADR 0004 said 2024-11) from the stored data and make both documents agree.

## 6. How the code is organised

- `backend/` (Java 21, Spring Boot, Flyway, TimescaleDB):
  - `ingestion` (importers, `RawWriter`, import batches and progress);
  - `metrics` (`Trimp`, `Baseline`, `ActivityLoadService`, `DayFeatureService`, model versions and activation: `activated_at`/`activated_by`/`retired_at`, refused for another feature version, a contract without `schema_version` 2, a candidate older than the ACTIVE version, or one without stored pass marks or test window; activation freezes `holdout_targets`, ADR 0009);
  - schedules (stage 2-1a, ADR 0019), package `planning`: `SessionTypes` (the 12 types and the demand a new session starts with [configurable]), `SessionValidator` (pure: checks and completes one session; the import reuses it), `PlanService` (one ACTIVE plan per athlete; a session outside the plan stretches it; every create/edit/status change/delete writes a `planned_session_revision` row in the same transaction), `PlanRepository`, `PlanImportParser` / `PlanImportService` (spreadsheet import, PR 2: CSV with `,` or `;` and XLSX first sheet read without extra libraries; preview saves nothing; commit re-reads the file and saves every row through `PlanService.createSession` in one transaction, all or none; every row is checked by `SessionValidator`), `PlanController` (also `POST /api/athletes/{id}/plan-import/preview` and `/commit`, coach only; `/api/athletes/{id}/plans`, `/api/plans/{id}`, `/api/athletes/{id}/sessions`, `/api/sessions/{id}`, `/api/sessions/{id}/revisions`, `/api/plan/session-types`; reads via AccessPolicy, writes coach only);
  - `security` (`SessionGuard` re-checks the user on every request, R-09; `LoginThrottle`), `athletes`; package `adaptation` (the Phase 2 tables' shape is fixed by ADR 0018, V22; `adaptation/Phase2SchemaIT` tests it).
  - Settings store (stage 2-2, ADR 0021), package `adaptation.settings`: `SettingsCatalog` (every [configurable] value of the engine: dotted path, default, rationale; coach-wide), `SettingsService.current()` (the newest `settings_version`; saves the defaults as version 1 on an empty table, and a new version when an update adds a value), `SettingsRepository` (insert-only), `SettingsController` (`GET /api/settings`, `/api/settings/versions`, coach). `planning.DemandDefaults` reads `planning.defaultDemand.*` for new sessions.
  - Signals (stage 2-2, ADR 0021), package `adaptation.signals`: `Ewma` (same as `trends.ts`), `LoadSignals` (pure: acute/chronic/ACWR total and running-only, Foster monotony and strain, running km/min), `SignalSettings`, `SignalRepository` (reads `day_features` loads, running activities; replaces `day_signals` rows of one signals + settings version), `SignalService` (`SIGNALS_VERSION` s3 since the 2026-11-19 tolerance fix; whole history to today; called after Recompute features and after a sync that recomputed, never inside the golden replay), `SignalController` (`GET /api/athletes/{id}/signals`, `POST …/signals/recompute` coach), `SignalsCli` (`signals --athlete N`). PR 2: `Readiness`, `SleepDebt`, `Subjective`, `Tolerance` (pure), `DaySignals`, `ToleranceController` (`GET /api/athletes/{id}/tolerance`, `POST …/tolerance/override` coach; table `tolerance_override`, V25, append-only); also recomputed after a check-in. Frontend: `src/signals.ts`, `components/SignalsSection.tsx` (Trends), `components/ToleranceCard.tsx` (Athletes).
  - daily scoring (stage 1b-1, ADR 0012): `ScoringContract` (reads coefficients, refuses anything but `schema_version` 2), `DailyScorer` (ADR 0008 S1–S8, pure arithmetic, checked by `DailyScorerParityTest` on the committed fixtures), `ScoringService` (the one entry point; scores the whole grid, replaces only the requested dates of the ACTIVE model's rows, keeps other models' rows), `ScoreController` (`POST /api/athletes/{id}/scores/recompute`, `GET /api/athletes/{id}/scores`), `ScoringCli` (`score` command; the app starts without its web server and skips import housekeeping);
  - golden check (R-14, ADR 0017): `GoldenService` (freeze; check = replay in a transaction that is always rolled back: rows of import batches after the frozen watermark are removed, then `FeatureRecomputeService.recompute` runs), `GoldenHasher` (pure: salted per-column / per-JSON-leaf hashes, numbers rounded to 10 significant digits), `GoldenFile`, `GoldenCli` (`golden freeze|check`, exit 0 / 1 code changed / 3 inputs changed / 2 cannot run / 64 bad arguments), `GoldenProperties` (`app.golden.dir`, `/backups/golden` in Docker). `--scores-only` replays only the scoring (used by CI on the parity fixture). Never change what it checks while the holdout runs (ADR 0008 frozen);
  - live holdout (stage 1b-3, ADR 0009 rules, ADR 0013 choices), package `metrics.holdout`: `HoldoutEvaluator` (pure arithmetic: window, one group per day and score, rates with `Wilson` intervals, MAE/Spearman as in `calibration/metrics.py`, naive baseline, verdict), `HoldoutService` (reads the version's own `derived_day_metrics`, finished `whoop_reference` cycles, the frozen `holdout_targets`; a retired version only sees Whoop rows imported before its `retired_at`), `HoldoutController` (`GET /api/athletes/{id}/holdout[?version=]`, `/holdout/versions`, `/holdout/report`, `/holdout/feed`; coach only, read-only), `DecisionReport` (the static HTML decision report, built on request, never stored). Every Recompute features run is written to `feature_recompute_log` (V16) and marked on the view;
  - Trends screen (stage 1b-2b, ADR 0016), package `metrics.trends`: `TrendsView` (pure: lines stored values up by date, fills nothing), `TrendsService` (reads `day_features` and the ACTIVE model's `derived_day_metrics`), `TrendsController` (`GET /api/athletes/{id}/trends`, AccessPolicy). Constants under `app.trends`;
  - Today screen (stage 1b-2a, ADR 0014), package `metrics.today`: `TodayView` (pure: picks today's row or the last scored day, the state, the strain target and the plain-words inputs; never scores), `TodayService` (reads `derived_day_metrics` and `day_features.ln_rmssd` for the HRV baseline; today in the athlete's timezone from the `Clock` bean), `TodayController` (`GET /api/athletes/{id}/today`, AccessPolicy). Display constants under `app.today` in `application.yml`;
  - Garmin revisions (R-10, ADR 0010): `RawWriter` stores a Garmin wellness or sleep record that comes again with different values as a revision row (`<id>@<batch>`); readers use the newest (`daily_wellness_merged`, `sleep_session_current`, `activity_with_cycle`, V17);
  - Check-in (stage 2-1b, ADR 0020), package `adaptation.checkin`: `CheckinValidator` (pure rules), `CheckinRepository` (insert-only; reads only `subjective_current`), `CheckinService` (scope, date in the athlete's timezone), `CheckinController` (`GET/POST /api/athletes/{id}/checkins…`, `GET /api/checkins/recent` coach only), `app.checkin.max-backdate-days`. Frontend: `pages/CheckinPage.tsx`, `src/checkin.ts`.
  - Garmin seed (ADR 0020 amendment), package `adaptation.checkin`: `GarminEffort` (pure mapping), `GarminSeedService` (idempotent, never over an existing check-in), `GarminSeedCli` (`garmin-seed`); `ingestion`: `RawWriter.activityEffort` fills `activity_effort` (V24).
  - Migrations V1–V25 (V25 running-tolerance override table and the corrected `whoop_reference.cycle_date` comment, ADR 0021; V24 `activity_effort`, ADR 0020 amendment; V23 check-in: `source`, `on_behalf`, niggle constraint, append-only trigger on `subjective_log`, ADR 0020; V22 Phase 2 schema corrections, R-13, ADR 0018; V21 removes sleep rows stored by the first Garmin syncs, ADR 0015 D11; V20 `garmin_sync_run` and `feature_recompute_log.source`, ADR 0015; V19 `garmin_credential`, ADR 0011 D3; V18 `app_user.password_changed_at`, R-09; V12 removes unfinished Whoop cycles, R-06; V13 records activation and retirement times, R-04; V14 adds `derived_day_metrics.feature_version`, R-15; V15 adds `model_version.holdout_targets` and fills it in for the ACTIVE m15, R-07; V16 adds `feature_recompute_log`, ADR 0013 D6; V17 Garmin revisions, ADR 0010). **Never edit a merged migration; add a new one.**
- `calibration/` (Python 3.12, package 0.6.0, version in `calibration/__init__.py`): `pipeline.run()` is a pure function of `day_features` (plus an optional `context` with the profile values, read by `__main__` from `activity_load`). CLI: `--test-from` / `--test-to` fix the test window. `FEATURE_VERSION` (`f3`) must match `app.metrics.feature-version` (a test checks this). The **scoring contract** for 1b-1 is **ADR 0008**: the coefficients JSON, whose shape is `calibration/contract.schema.json` (`schema_version` 2: `calibration_version`, `feature_version`, `feature_config` {hr_max, hr_rest, sex_assumed}, `strain` with `clip` [0, 21], `sleep` with `performance_clip` [0, 100], `recovery`, `bands`, and `training_ranges` {min, p01, p99, max, n} per model part and input, for drift flags), *plus* the rules that turn them into daily scores (day grid, yesterday's strain, 7-day shortfall, gaps and their named reasons, clipping, rounding, bands, low-confidence flag), each with a worked example. The **parity fixtures** for 1b-1's Java test are in `backend/src/test/resources/parity/` (`adr0008`, `load`, `zones`), produced by the reference scorer `calibration/parity.py` (R-03; ADR 0008, Consequences).
- `frontend/` (React + TypeScript): **Today** (landing page, all roles; wording helpers in `src/today.ts`), **Trends** (all roles; maths in `src/trends.ts`, chart in `components/LineChart.tsx`), **Plan** (all roles, coach edits and imports a plan from CSV/XLSX with a preview, `components/PlanImport.tsx`; athletes read-only; helpers in `src/plan.ts`, stage 2-1a), Coverage, Imports, Calibration (coach; shows the scoring result after Recompute features), Holdout (coach; the live comparison with Whoop, stage 1b-3) and Athletes (coach). Wide tables scroll inside their card (`.table-scroll`), so pages don't scroll sideways at phone width.
- `tools/inspect_exports.py`: a read-only export summary, Python 3.9+, never prints free text.
- `garmin-sync/`: the Garmin sidecar (stage 1b-4, ADR 0015). `auth.py` signs in with `garminconnect` (pinned in `requirements.txt`; `garth` is deprecated and unused), keeps a waiting MFA login in memory only, returns tokens as JSON text and never writes a token file; `adapter.py` fetches days (`GarminConnectAdapter`); `server.py` serves `/health` and, behind `X-Sync-Secret`, `/auth/login`, `/auth/mfa` and `/sync/days`, answering with fixed error codes only. Logs only method, path without query string and status (ADR 0011 D9). `tests/test_library_contract.py` guards the library parts we rely on.
- Garmin connection in the backend (stage 1b-4, ADR 0011/0015), package `garmin`: `GarminConfigCheck` (pure startup check of `GARMIN_TOKEN_KEY`/`GARMIN_SYNC_SECRET`), `GarminSetup` (logs one warning and disables sync when either is wrong; the app still starts), `TokenCipher` (AES-256-GCM, athlete id as AAD, `key_id`), `GarminCredentialRepository` (V19), `SidecarClient` (JDK HTTP client, secret in the header, fixed error codes), `GarminConnectionService` and `GarminController` (`GET/DELETE /api/athletes/{id}/garmin`, `POST …/garmin/connect`, `POST …/garmin/mfa`, coach only). Frontend: `components/GarminCard.tsx` on the Imports page.
  - Morning run (stage 1b-4 PR 3, ADR 0015 D9): `MorningSchedule` (pure: due from the configured local time until midnight, once; tested with a fake clock incl. DST), `GarminSyncService.morningDue` (called every minute by `GarminSyncScheduler`), `GarminSyncStatusController` (`GET …/garmin/sync/status`, AccessPolicy). Frontend: `src/sync.ts` (banner choice, pure), `components/LastSync.tsx`. Holdout guard (D13): `HoldoutRepository.syncedNights`, group `WAITS_FOR_EXPORT` in `HoldoutEvaluator`.
  - Garmin sync (stage 1b-4 PR 2, ADR 0015): `GarminSyncService` (one run: fetch today and the previous 2 days through `SidecarClient.fetchDays`, import as a `GARMIN_SYNC` batch with `ImportRunner.runSync`, then `FeatureRecomputeService` if anything was added; retries and RECONNECT_NEEDED), `GarminSyncRunRepository` (V20), `GarminSyncScheduler` (retries every minute; off in tests and for command-line runs), `GarminSyncCheckService` (the rolled-back "Check against export", `ImportRunner.dryRunSync`; with `SleepFieldSearch`, which finds the live field matching each stored sleep value), `GarminSyncController`. In `ingestion.garmin`: `GarminApiShapes` (live records → export records; the one place to fix a live field name) and `GarminExportImporter.importSync`. `metrics.FeatureRecomputeService` is what the Recompute features button runs. Frontend: `components/GarminSync.tsx`.
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
  - Runs once per push to a pull request, on every push to `main` (merges), and by hand from the Actions tab ("Run workflow"). Pushes to other branches without a PR don't run it.
  - A newer push to the same PR cancels the older, still-running run; runs on `main` are never cancelled.
  - The repository is private, so Actions minutes are limited (2,000 a month on the free plan): don't add triggers or jobs without weighing the cost. `images` (all five Docker images) runs only on pull requests.

### Notes for the cloud session environment (not the coach's machine)
- The Docker daemon may need starting: `nohup dockerd >/tmp/dockerd.log 2>&1 &`.
- Image builds inside Docker can't download packages (TLS proxy). For an end-to-end check:
  - build the jar (`./gradlew bootJar`) and the frontend on the host;
  - run `timescale/timescaledb:2.17.2-pg16` on port 5433 and start the jar against it;
  - use `curl` with the CSRF cookie flow;
  - run the calibration from a host venv with `PGHOST/PGPORT/…` set.
- Python: create the venv with the system `python3.12 -m venv` plus `pip`.
- `Fixtures` (backend tests) can write synthetic export files for manual runs.

---

## Change log

Newest first. Moved here from the "Last updated" line at the top (R-15); the wording is unchanged.

### 2026-11-19

- **tolerance fix, the decay counts only the current break** (the coach's tolerance read 12.4 km/week while running about 37: every week under half the 62 km peak since February counted as time away, 9 in all, −80 %; now interrupted weeks are counted back from the day until two normal weeks in a row (`signals.tolerance.returnWeeks` = 2, coach, [configurable]); the cap factor stays 1.0 (flagged); signals version s3; new settings version on first read; ADR 0021 amendment; no feature, `FEATURE_VERSION` or scoring change, golden check stays at 0 differences).

### 2026-11-18

- **stage 2-2 PR 2, readiness, sleep, check-ins, tolerance and screens** (recovery trend over 3/7 days and band streaks from 2025-09-17 on; 7-night sleep debt with payback, never below 0; check-in flags and niggle episodes; session demand; running tolerance in km/week = best clean 4-week average in 12 months, −10% per interrupted week beyond the first, capped by the chronic running volume, with the derivation and a coach override kept as a history (V25); signals version s2; recomputed after check-ins and overrides; Trends gets running vs tolerance and an Engine signals card, Athletes a tolerance card; ADR 0021 D9–D12; no feature, `FEATURE_VERSION` or scoring change, golden check stays at 0 differences).
- **stage 2-2 PR 1, settings store and load signals** (every [configurable] engine value in one versioned, append-only settings document, coach-wide (coach); the defaults become version 1 on first use; the default demand per session type moved into it; daily Edwards load (Banister as a setting) read from the same column as Trends; EWMA acute 7 d / chronic 28 d and ACWR, total and running-only; Foster monotony and strain; running km and minutes over 7 and 21 days; stored in `day_signals` with `signals_version` s1 and the settings version; computed after Recompute features and after a sync that recomputed, and by `signals --athlete N`; ADR 0021; no migration, no feature, `FEATURE_VERSION` or scoring change, golden check stays at 0 differences).
- **stage 2-1a PR 2, spreadsheet import of a plan** (Plan tab → "Import from spreadsheet", coach only: choose a CSV or XLSX with the columns `date,type,demand,title,workout,distance_km,duration_min,notes` (DD/MM/YYYY); a preview lists every row with plain errors such as "row 7: unknown type 'tempo'" and notes (same date already has a session, date in the past, plan will be stretched); nothing is saved until Import, which is all or nothing in one transaction; imported sessions follow exactly the rules of typed ones (one ACTIVE plan, stretch, a history row each); a session already on the date is kept and the new one is added next to it; no migration, no feature or score change, golden check stays at 0 differences; ADR 0019 import note, D8–D12; screenshots `docs/screenshots/2-1a/import-*.png`)
- **stage 2-1b follow-up, Garmin RPE seed** (the coach's real values: `workoutRpe` 10…60 in steps of 10, `workoutFeel` 25/50/75/100; RPE = `workoutRpe` ÷ 10 [configurable], `workoutFeel` stored but not seeded; V24 `activity_effort` keeps both as received, written by the Garmin export importer even for activities already stored; seed = one `GARMIN_SEED` row per activity, skipped when the activity has any check-in, so a manual answer always wins; runs after every Garmin export import and as `docker compose run --rm app garmin-seed --athlete 1`; not for the daily sync; ADR 0020 amendment; no feature, `FEATURE_VERSION` or scoring change, golden check stays at 0 differences)
- **stage 2-1b follow-up, part A** (`tools/inspect_exports.py` prints counts and distinct values of `workoutRpe` / `workoutFeel`, numbers only; the raw `activity` rows do not keep the summary JSON, so the values must come from the export; no app, migration or score change)
- **stage 2-1b, athlete check-in** (Check-in tab and a one-tap button on Today; RPE of the day 1–10, soreness 1–5, niggle with body location, severity and "it's gone", injury, illness, note; scope whole day / activity / planned session; `subjective_log` is append-only by a database trigger and read only through `subjective_current`; the coach sees everyone's recent check-ins and may log on an athlete's behalf, marked "by coach"; V23, ADR 0020; screenshots in `docs/screenshots/2-1b/`; no feature, `FEATURE_VERSION` or scoring change, golden check stays at 0 differences; the Garmin RPE seed followed the same day, see the entry above)
- **stage 2-1a PR 1, plan entry and the Plan screen** (Plan tab: create a plan, add/edit sessions with date, type, demand, workout, distance, duration, optional strain, notes, status; one ACTIVE plan per athlete; a session outside the plan stretches it (coach); every change written to `planned_session_revision` with before/after and who; Skipped/Replaced offered before delete, which also removes the history; default demand per type [configurable]; athletes read their own plan only; ADR 0019; no migration, no feature or score change, golden check stays at 0 differences; screenshots `docs/screenshots/2-1a/`)

### 2026-11-17 (R-15, documentation clean-up)

- Status lines in `docs/REVIEW.md` now carry PR numbers; ADR 0002, 0003, 0004, 0005 and 0007 corrected (see R-15 in `docs/REVIEW.md`); the "Last updated" line above is now a short summary and the history is this section; PROMPTS shared preamble extended. No code or migration changed.

### 2026-11-17

- **stage 1b-4 PR 3, the morning run — Phase 1 complete** (the Garmin sync runs by itself at 07:00 in the athlete's timezone [configurable], or within a minute of the Mac waking that day, once; a successful Sync now after 07:00 counts; README gives `sudo pmset repeat wakeorpoweron MTWRFSU 06:55:00`; "Last sync" on Today and Imports; Today's banner tells apart "Garmin needs you to connect again", "sync failed" (and "trying again at"), "syncing" and "Garmin had no data for today yet"; `GET /api/athletes/{id}/garmin/sync/status`; **Holdout guard**: a compared day scored from a synced night is `WAITS_FOR_EXPORT` for every score until an exported night replaces it and it is rescored, shown as "N compared days wait for a Garmin export"; an exported night equal to a synced one is now stored as the export's revision (bug found, test first); ADR 0015 D9/D13, pointers in ADR 0009 H4, 0010 D1, 0013 D3, wording in ADR 0014; no feature, `FEATURE_VERSION` or scoring change, golden check stays at 0 differences)
- **R-13 Phase 2 schema** (stage 2-0; V22, ADR 0018: `planned_session.is_key` dropped, `demand` is the one key marker; suggestions get a counterpart session, settings/signals version, engine run date and a database-computed `dedup_key`, so a repeated run can't store the same pending suggestion twice; session links + append-only `planned_session_revision`; new `settings_version` and `day_signals`; check-ins get `injury`/`niggle_resolved` and the view `subjective_current` (latest per day and session/activity/day); Phase 2 inputs never go into `day_features`; ACWR/Foster on daily load; no feature or score change, the golden check stays at 0 differences)
- **R-14 golden check** (`golden freeze` / `golden check` next to `score`: hashes of 60 days of real features and scores in git-ignored `./backups/golden/`, no values; the check replays from the raw data as it was at freeze time in a rolled-back transaction, so new exports and syncs never look like code changes, and "inputs changed" (profile, model, raw rows) is reported apart from "code changed"; exit 0 / 1 / 3; run it after every update; ADR 0017; no change to features or scores)
- **stage 1b-4, live breathing rate (coach's option B)** (no live field equals the export's breathing rate or SpO2; synced nights use Garmin's live values, about 3 recovery points off on average with m15, up to about 6, reading high; Today marks such days approximate; an exported night always wins over a synced one; ADR 0015 D12)
- **stage 1b-4, sleep field search v2** (first search: only lowest SpO2 matches the export; the search now also averages Garmin's timed lists over the time asleep and shows how close each field comes; diagnosis only, no change to what is stored; ADR 0015 D11)
- **stage 1b-4 fix, sleep averages** (on the coach's data the live sleep summary gives breathing rate, SpO2 and sleep stress as whole numbers; the sync now never stores those, looks for the precise fields in the whole answer and otherwise leaves them empty; the check lists which field matches the export; V21 removes the sleep rows the first sync stored; ADR 0015 D11)
- **stage 1b-4 PR 2, Garmin fetch, Sync now and Check against export** (the sidecar fetches summary, sleep, HRV, monitoring and activity FIT files; the app turns them into export records and imports them through the export's own handlers, then runs the same recompute as the button; retries after 10 and 30 min; V20 `garmin_sync_run` and `feature_recompute_log.source`; Holdout markers show only manual recomputes; ADR 0015 D5–D8, D10).

### 2026-11-16

- **stage 1b-2b, the Trends screen** (HRV, resting HR, recovery bands, strain with lower-confidence markers, sleep, acute 7 d vs chronic 28 d EWMA on Edwards load; ranges 4 weeks / 3 months / 12 months; gaps are gaps; phone tabs wrap; `GET /api/athletes/{id}/trends`, ADR 0016; display only, no score or feature change)
- **stage 1b-4 PR 1, Garmin connection** (Connect / MFA / Disconnect on the Imports page; tokens only, AES-256-GCM in the app, V19 `garmin_credential`, sidecar behind `X-Sync-Secret`; `garth` is deprecated, so the sidecar uses `garminconnect` 0.3.20 alone, ADR 0011 amendment and ADR 0015; nothing is fetched yet)
- **stage 1b-2a, the Today screen** (landing page; fresh / missing with reason / stale, never an older recovery as today's; personal strain target [configurable]; HRV in % of the 30-day baseline; `GET /api/athletes/{id}/today`, ADR 0014; display only, no score or feature change)
- **R-11 Garmin credentials** (ADR 0011, design only: the coach chose option (a), a renewable token encrypted with `GARMIN_TOKEN_KEY`, never the password; stateless sidecar behind `GARMIN_SYNC_SECRET`; `.env.example` documents both; the sidecar no longer logs query strings)
- **R-12 test/CI gaps closed** (DST and timezone day-assignment tests, Vitest for the frontend, CI builds every Docker image on pull requests; no behaviour change)
- **R-09 sessions** (a removed login or changed password ends its sessions at once, V18, login slowdown after 5 wrong passwords, API docs need a login, ADR 0005)
- **stage 1b-3 verified on the coach's data** (window 2026-11-09 → 11-14, 6 of 6 days compared, page n = query n; first Garmin revision, 2026-11-09, applied)
- **stage 1b-3 PR 3, keeping the holdout fed** (Whoop + Garmin exports every week or two, "Keeping it fed" panel, `/holdout/feed`, README section 7)
- **R-10 raw revisions** (ADR 0010, V17: a Garmin day exported again replaces the half-finished version; no feature change, holdout not restarted)
- **stage 1b-3 PR 2, the Holdout page and decision report** (coach tab "Holdout", `GET /api/athletes/{id}/holdout/report`)
- **stage 1b-3 PR 1, the live holdout calculation** (`metrics.holdout`: Wilson intervals, ADR 0009 H2–H8 on synthetic tests, `GET /api/athletes/{id}/holdout`, V16 Recompute features log, ADR 0013); ADR 0009 live holdout protocol and frozen holdout targets (R-07, V15).

### 2026-11-15

- **stage 1b-1 daily scoring** (ScoringService, REST + CLI, Recompute features rescores, V14, ADR 0012; parity test green on all three fixtures); ADR 0008 scoring contract (R-01); backup hardened and restore-tested (R-08); unfinished Whoop cycles (R-06); versioned coefficients contract, calibration 0.6.0 (R-02); parity fixtures and feature-version guard (R-03); activation time recorded and unsafe activations refused (R-04, V13). **Model m15-20261115 (f3, contract v2) is ACTIVE since 2026-11-15 19:47:54 UTC**; m13 is RETIRED at the same instant; a candidate older than the ACTIVE version (m14) can no longer be activated.

