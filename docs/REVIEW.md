# Project review – 2026-11-13, at 8d5a7d7

## 1. Verdict (≤ 10 lines)

The project is in good health: small, readable, well tested for its size, and honest about its own
limits. Nothing found invalidates the Phase 1a PASS, but three things should happen before or
alongside stage 1b-1:

1. **The hand-over from the calibration job to the daily scoring is not written down precisely
   enough.** Half of what the scores depend on (yesterday's strain, sleep shortfall, sleep
   performance, rounding, what to do when an input is missing) exists only as Python code. A Java
   copy written from the current description would disagree with Python on some days (R-01–R-03).
2. **Days without a recorded night's sleep are probably assigned wrongly** (morning workouts counted
   on the previous day). One pasted query tells us how often this happened; if it is common, fix it
   *before* activating a model, because the fix needs a new calibration (R-05).
3. **Two silent data-loss risks:** the nightly backup can write an empty file and report success
   (R-08), and each Whoop re-import freezes the unfinished current day with a too-low strain (R-06).

**1b-1 can start after R-01 → R-04 and the R-05 query** (about 3–4 working days). Do not press
Activate until then.

## 2. What was checked

**Read in full:** `docs/PLAN.md`, `SPEC.md`, ADRs 0001–0007, `docs/PROMPTS.md`; migrations V1–V11;
`application.yml`; backend packages `metrics`, `ingestion` (incl. `garmin`, `whoop`), `security`,
`athletes`, `common`; `calibration/` (all modules, tests, `targets.json`); `garmin-sync/`;
`docker-compose.yml`, `deploy/backup/`, `.github/workflows/ci.yml`, Dockerfiles, `nginx.conf`,
`.gitignore`, the README's backup section. **Skimmed:** `frontend/src` (pages, API client, hooks),
backend tests (`MetricsIT`, `ImportIT`, `SecurityIT` read; others by name), `tools/inspect_exports.py`
(not reviewed line by line).

**Test suites run in the review environment:**

| Suite | Command | Result |
|---|---|---|
| calibration | `ruff check . && ruff format --check . && python -m pytest -q` | clean, **20 passed** |
| tools (Python 3.12) | same | clean, **5 passed** |
| garmin-sync | same | clean, **3 passed** |
| frontend | `npm run lint && npm run typecheck && npm run build` | all clean |
| backend | `cd backend && ./gradlew build` | **BUILD SUCCESSFUL: 47 tests in 11 classes, 0 failures, 0 skipped** (with `-Werror`; Testcontainers on TimescaleDB). The first attempt failed only because Maven Central rate-limited the dependency download (HTTP 429); the retry passed. |
| tools on Python 3.9 | – | **not run**: no `python3.9` in this environment. CI covers it (`tools-py39`). |

**Extra probe (not committed):** ran `pipeline.run` on the synthetic table to inspect the real
coefficients JSON and edge cases. Results are quoted in R-01 and R-03.

**Could not be checked, and why:**
- Anything about the coach's real data (it exists only on their Mac). Every such point below is
  marked SUSPECTED and comes with a read-only query.
- The stored coefficients and status of the PASS model (same reason).
- Whether the Whoop export leaves the current cycle's end time blank (no real export; R-06).
- Behaviour of the Docker images end to end (`docker compose up`): images were not built here (image builds cannot download packages in this environment, PLAN §7).

**Results of the coach's queries (added 2026-11-13, after the review was written):**

| Query | Result | Meaning |
|---|---|---|
| R-05, workouts moved to the previous day | 0 of 483 | The 36-hour rule has not misplaced any workout. |
| R-05, days without a Garmin sleep | 2 of 301 | Too few to matter. |
| R-05, workouts with long pauses | 103 of 482; 1,566 min in total, median 8.3 min, max 74 min | Confirmed and sized: an estimated 0.05 strain on a typical affected day and about 0.3 on the worst (estimate, not measured on the real model), against a strain error of 1.91. Fix at the next recalibration. It matters more if the zones form is ever selected, where those minutes would count in full. |
| R-06, dates with two Whoop cycles | 0 | No Whoop day is silently dropped. |
| R-05, HRV alignment | r = 0.92 same day (0.33 / 0.40 a day off) | Garmin and Whoop overnight HRV are on the same day. |
| R-06, unfinished Whoop cycles | 1, dated 2026-11-09 | Confirmed: the last test day carries a partial Whoop strain. |
| Active model | m13-20261113 (f3), ACTIVE | Activated on 2026-11-13. |

Consequences: **R-05 needs no feature change now** (no `f4`, no recalibration for this reason); the
day-boundary rule and the pause double count are recorded as known limitations to fix at the next
recalibration. **R-06 is confirmed** and keeps its priority. Model m13 stays active until the R-02
re-run reproduces it with the new contract.

**Reviewed and found sound (no work item):** Garmin unit handling (ms/cm conversions in
`GarminFields`, with the 12 m/s guard); the wake-day rule for Whoop and its V7 migration; trailing
baselines that exclude the current day (`Baseline.of`); train-only fitting of every model parameter,
standardisation constant and the sleep reference (no train→test leakage found in `pipeline.run`);
role checks on every existing endpoint (`@PreAuthorize` or `AccessPolicy.requireAthlete`); CSRF and
session-fixation handling; the sandboxed calibration report; import deadlock fix (V9).

## 3. Work items

| ID | Title | Priority | Type | Size | Depends on | Blocks stage |
|---|---|---|---|---|---|---|
| R-01 | Write down the scoring rules that today exist only in Python | P0 | design | S | – | 1b-1 |
| R-02 | Emit a versioned coefficients contract with training ranges and a profile snapshot (done) | P0 | design | M | R-01 | 1b-1 |
| R-03 | Generate a committed parity fixture and guard the feature version in CI (done) | P0 | test-gap | S | R-02 | 1b-1 |
| R-04 | Record when a model was activated and refuse unsafe activations | P0 | bug | S | R-02 | 1b-1, 1b-3 |
| R-05 | Check and fix day assignment when no night's sleep was recorded | Query done; fix deferred to P2 | bug | M | – | – |
| R-06 | Stop freezing unfinished Whoop cycles on import (done) | P1 | bug | S | – | 1b-3 |
| R-07 | Fix the live-holdout rules in writing before looking at holdout data | P1 | design | S | R-04 | 1b-3 |
| R-08 | Make the nightly backup fail loudly and prove it restores (done) | P1 | bug | S | – | – |
| R-09 | End sessions when a login is removed or its password changes | P1 | security | S | – | 1b-2 (athlete access) |
| R-10 | Define how re-fetched Garmin days replace earlier partial ones | P1 | design | M | – | 1b-4 |
| R-11 | Decide how Garmin credentials are stored before writing sync code | P1 | security | S | – | 1b-4 |
| R-12 | Add timezone/DST tests, a frontend test runner and an image build to CI | P1 | test-gap | M | – | 1b-2, 1b-4 |
| R-13 | Correct the Phase 2 tables before anything writes to them | P1 | design | M | – | 2-1, 2-3 |
| R-14 | Build the local golden-file check the spec asks for | P2 | test-gap | M | 1b-1 | – |
| R-15 | Bring documents back in line with the code | P2 | docs | S | – | – |

---

### R-01: Write down the scoring rules that today exist only in Python
- **Priority / Type / Size:** P0 / design / S
- **Status:** CONFIRMED; done 2026-11-15 (ADR 0008)
- **Problem:** ADR 0007 and PLAN §6 describe the contract as "`_strain_coefficients` plus the
  recovery terms, sleep and bands". But three recovery/sleep inputs are not columns of
  `day_features`; they are computed inside `pipeline.run` as a chain: strain → `prev_strain` →
  `shortfall` → sleep need → `sleep_perf` → recovery. Their edge rules (missing days, clipping,
  rounding, which value feeds the next step) are implicit in pandas calls. This also bends the
  "feature engineering lives only in Java" rule: today these derived inputs exist only in Python.
- **Evidence:**
  - `calibration/calibration/pipeline.py:119` `daily["prev_strain"] = daily["strain_pred"].shift(1)` (after a reindex to consecutive calendar days, `:36-37`).
  - `pipeline.py:126` sleep need uses `daily["prev_strain"].fillna(0)`, while `sleep.fit` (`sleep.py:23`) drops those rows and recovery (`pipeline.py:138`) requires `prev_strain` to be present: three different treatments of the same missing value.
  - `sleep.py:18-19` shortfall: missing nights count as zero deficit; `.shift(1).rolling(days, min_periods=1)`.
  - `metrics.py:32-37` bands are taken from the **unrounded** prediction; `derived_day_metrics.recovery_pct` is `INT` (V3:9). Probe on synthetic data: 2 of 60 test days change band if the score is rounded first.
  - Probe: removing one day's `background_load` removed the **next** day's recovery (60 → 59 test rows). In daily use, one day with the watch off means no strain that day and no recovery the next morning.
  - Recovery term for a negative-sign feature stores a signed center (`"feature": "prev_strain", "sign": -1, "center": -9.33`): contribution = `weight · (sign·x − center) / scale` (`recovery.py:50`). Easy to get wrong in Java.
- **Why it matters:** the 1b-1 parity test will either fail on edge days or, worse, pass on a happy-path fixture while Java and Python disagree on real days with gaps. The live holdout would then measure a different model from the one that passed.
- **Proposed change:** add `docs/adr/0008-scoring-contract.md` (no code). It must state, as numbered rules with one worked example each:
  1. the day grid (consecutive calendar days from the athlete's first to last `day_features` date);
  2. strain per form, clip to 0–21, when it is null (load form: load or background null; zones form: any zone column null), and that low-coverage days are scored and flagged;
  3. `prev_strain` = unrounded strain of the previous calendar day;
  4. shortfall exactly as `sleep.add_shortfall`;
  5. sleep need (with the missing-`prev_strain` rule) and sleep performance (clip 0–100, null when sleep minutes are null);
  6. recovery formula with the signed-center convention, clip from `recovery.clip`, null when any term input is null, and a named reason per missing input;
  7. rounding: stored values are rounded half-up at the end; bands are computed on the unrounded value (matches calibration);
  8. flag propagation: recovery is "lower confidence" when yesterday's strain was low coverage.
  Where the three treatments of missing `prev_strain` conflict, **document today's Python behaviour as the rule** (so the active model stays valid) and list the inconsistency as a known limitation to revisit at the next recalibration.
- **Out of scope:** changing any Python or Java behaviour; changing `FEATURE_VERSION`; moving the derived inputs into `day_features`.
- **Acceptance criteria:**
  - [x] ADR 0008 exists; every rule cites the Python line it mirrors.
  - [x] Each rule has a small numeric example that R-03's fixture will contain.
  - [x] ADR 0007 and PLAN §6 point to ADR 0008 as the contract.
  - [x] The coach has answered the question below.
- **Verification:** documentation only; `cd calibration && python -m pytest -q` still passes (nothing changed).
- **Coach decision needed:** yes. "If the watch was off all day yesterday, we cannot compute yesterday's strain. Should this morning's recovery (a) be shown as 'not available' with the reason, or (b) be shown anyway, marked lower confidence, assuming a typical day yesterday?" Recommendation: **(a) for now**, because that is what the calibrated model does; (b) needs a new calibration. **Coach's answer (2026-11-13): (a); confirmed 2026-11-15 and recorded in ADR 0008 (C1–C3).** The coach also noted that the exports are a few days old, so recent days have no data at all: that is the "stale data" case (no score, "last data from …"), separate from a day with the watch off.
- **Opus prompt:**
  ```text
  Task R-01: write ADR 0008 "Scoring contract" (documentation only, no code changes).

  Goal: the daily scoring service (stage 1b-1, Java) must reproduce the Python calibration
  pipeline exactly. Today several rules exist only as pandas code in
  calibration/calibration/pipeline.py (run), strain.py (predict_model), sleep.py and recovery.py.
  Write them down as numbered, testable rules in docs/adr/0008-scoring-contract.md.

  Cover: the consecutive-calendar-day grid; strain per form with clipping and null rules;
  prev_strain (previous calendar day's UNROUNDED strain); the 7-day shortfall exactly as
  sleep.add_shortfall; sleep need (note pipeline.py line 126 uses prev_strain.fillna(0)) and sleep
  performance; the recovery formula weight*(sign*x - center)/scale with the signed-center
  convention; clipping; rounding (store rounded half-up, band from the unrounded value, as
  metrics.band does); which missing input makes which score null, with a named reason; how the
  low-coverage flag propagates to the next day's recovery.

  Constraints: describe current Python behaviour, do not change it. Where it is inconsistent
  (missing prev_strain is treated three ways), document it and list it under "Known limitations".
  Give one small numeric example per rule. Update the pointers in ADR 0007 and docs/PLAN.md §6.
  Do not touch FEATURE_VERSION, Java code, Python code or migrations.

  Done when: the ADR exists, each rule cites the Python line it mirrors, and I (the coach) have
  answered: when yesterday's strain is unknown, show "recovery not available" (recommended) or
  show it as lower confidence?

  Verify: cd calibration && ruff check . && ruff format --check . && python -m pytest -q
  ```

### R-02: Emit a versioned coefficients contract with training ranges and a profile snapshot
- **Priority / Type / Size:** P0 / design / M
- **Status:** CONFIRMED; done 2026-11-15 (calibration 0.6.0, `calibration/contract.schema.json`). Coach re-run and comparison query pending.
- **Problem:** the coefficients JSON has no schema version, no training ranges (needed for the drift flag 1b-1 must write), no record of the athlete profile and feature settings the features were built with, and no calibration package version. Its shape is only checked by one key-set assertion. Re-running calibration later cannot reproduce the PASS model's test window because there is `--test-from` but no `--test-to`.
- **Evidence:**
  - `pipeline.py:207-213`: keys are exactly `feature_version, strain, sleep, recovery, bands`.
  - `calibration/tests/test_calibration.py:82` is the only contract test: `assert set(r.coefficients) == {...}`.
  - Strain is clipped to 0–21 only in code (`strain.py:54`, `:78`); recovery carries `"clip"`, strain and sleep performance do not.
  - HRmax, HRrest and sex come from the live `athlete` row at recompute time (`ActivityLoadService.java:42-52`); `model_version` (V6:64-80) stores none of them. Editing the profile and pressing Recompute changes every load under a frozen model, silently.
  - `__main__.py:33`: only `--test-from`.
- **Why it matters:** 1b-1 cannot implement "drift beyond the training range" without ranges; Java cannot refuse a contract it does not understand; and a profile edit (e.g. a new HRmax) during the live holdout would quietly change the scores being judged.
- **Proposed change:** in `calibration/`:
  - add `"schema_version": 2` and `"calibration_version"` (package version);
  - add `"clip"` to `strain` (`[0, 21]`) and `"performance_clip": [0, 100]` to `sleep`;
  - add `"training_ranges"`: for every model input, including derived ones (`prev_strain`, `shortfall`, `sleep_perf`, the chosen load column, `background_load`, zone columns when used), `{min, p01, p99, max, n}` over the rows actually used to fit;
  - add `"feature_config"`: the values read from the newest `activity_load` rows (`hr_max`, `hr_rest`, `sex_assumed`) passed in by `__main__` (one small query in `data.py`); pipeline stays a pure function with an optional `context` argument;
  - add `--test-to`;
  - commit `calibration/contract.schema.json` (JSON Schema) and validate the output against it in a test (`jsonschema` as a dev dependency).
  Update ADR 0007/0008. Alternative considered: let Java compute ranges from `day_features` between `train_from` and `train_to` — rejected, because a later recompute would move the "frozen" ranges.
- **Out of scope:** changing how any model is fitted or selected; `FEATURE_VERSION`; Java code; the report layout beyond one line showing the contract version.
- **Acceptance criteria:**
  - [x] First, a failing test: the pipeline output validates against `contract.schema.json` (fails today: no `schema_version`).
  - [x] Test: every `recovery.terms[*].feature` and every strain/sleep input has a `training_ranges` entry with `min ≤ p01 ≤ p99 ≤ max`.
  - [x] Test: `--test-from X --test-to Y` limits the test window; coefficients are identical with and without `--test-to`.
  - [x] Test: both strain forms produce valid contracts.
  - [x] Package version bumped; `docs/PLAN.md` §6 updated.
- **Verification:** `cd calibration && ruff check . && ruff format --check . && python -m pytest -q`. Coach, after merging (do not Activate yet):
  `docker compose run --build --rm calibration --athlete 1 --test-from 2026-09-10 --test-to 2026-11-09`, then
  `docker compose exec db psql -U training -d training -c "SELECT n.version, n.passed, n.coefficients->'recovery' = o.coefficients->'recovery' AS same_recovery, (n.coefficients->'strain') @> (o.coefficients->'strain') AS same_strain FROM model_version n, model_version o WHERE n.athlete_id = 1 AND o.athlete_id = 1 AND n.id = (SELECT max(id) FROM model_version WHERE athlete_id = 1) AND o.id = (SELECT max(id) FROM model_version WHERE athlete_id = 1 AND passed AND id < n.id);"`
  Expected: `passed = t`, `same_recovery = t`, `same_strain = t`. If either is `f`, stop and report: the training data changed since the PASS run.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-02: version the calibration → scoring coefficients contract (Python only).

  Context: calibration/calibration/pipeline.py builds model_version.coefficients, which the Java
  daily scoring (stage 1b-1) will read. Today it has no schema version, no training ranges (needed
  for drift flags), no snapshot of the profile values the features were built with, and strain's
  0–21 clip is only in code. Read docs/adr/0007 and docs/adr/0008 first.

  Do: add schema_version (2), calibration_version, strain.clip [0,21], sleep.performance_clip
  [0,100], training_ranges ({min,p01,p99,max,n} for every model input INCLUDING the derived ones:
  prev_strain, shortfall, sleep_perf, the chosen load column, background_load, zone columns when
  the zones form is chosen; computed on the rows actually used to fit), and feature_config
  (hr_max, hr_rest, sex_assumed read from activity_load by __main__/data.py and passed in; keep
  pipeline.run a pure function). Add a --test-to option. Commit calibration/contract.schema.json
  and validate against it in tests (add jsonschema to the dev extras). Bump the package version.

  Constraints: do not change fitting, selection, targets or FEATURE_VERSION. Fixtures stay
  synthetic. No Java changes. Start with the failing schema-validation test.

  Done when: the acceptance tests pass (schema validation; a range for every input; --test-to
  works and does not change coefficients; both strain forms validate); ADR 0007/0008 and
  docs/PLAN.md §6 describe the new keys.

  Verify: cd calibration && ruff check . && ruff format --check . && python -m pytest -q
  End the PR with the two commands the coach must run (re-calibrate with
  --test-from 2026-09-10 --test-to 2026-11-09, then the SQL comparing the new coefficients with
  the previous passing version) and the expected output.
  ```

### R-03: Generate a committed parity fixture and guard the feature version in CI
- **Priority / Type / Size:** P0 / test-gap / S
- **Status:** CONFIRMED; done 2026-11-15 (`backend/src/test/resources/parity/`, `calibration/calibration/parity.py`, `calibration/tests/test_parity.py`). The fixture builder lives in `calibration/tests/parity_fixtures.py` because it needs the synthetic tables in `tests/`.
- **Problem:** stage 1b-1 asks for a Java/Python parity test "generated from the Python tests", but nothing produces such a fixture, and the existing synthetic table (`tests/synthetic.py`) has no gaps, no null inputs and no low-coverage days in the places where Java is most likely to differ. Separately, the feature version is typed by hand in two files with no check that they agree.
- **Evidence:** `tests/synthetic.py:49` only nulls `ln_rmssd_z` for the first 14 days; `calibration/__main__.py:18` `FEATURE_VERSION = "f3"` vs `application.yml:64` `feature-version: f3`; no CI step compares them (`.github/workflows/ci.yml`).
- **Why it matters:** a parity test on clean rows proves little; the mismatches are on edge days (see R-01). A feature-version mismatch makes the calibration read an empty or stale table (this already cost one iteration, PLAN §3 row m5).
- **Proposed change:** add `calibration/tools/make_parity_fixture.py` (or a `python -m calibration.parity` entry point) that builds a deterministic synthetic table with planted edge cases, runs `pipeline.run`, and writes two files to `backend/src/test/resources/parity/`: `day_features.csv` (the input columns, at database precision) and `expected.json` (the full coefficients plus, per day: strain, prev_strain, shortfall, sleep need, sleep performance, recovery unrounded and rounded, band, per-term contributions, null reasons). Planted cases: a missing calendar day; a day with null `background_load`; a low-coverage day; a null `sleep_minutes`; a null HRV day; a prediction within 0.5 of each band edge; raw predictions below 0 and above 100/21. Produce one fixture per strain form. Add a pytest that regenerates the fixture in memory and fails if the committed files differ, and a pytest that reads `application.yml` and asserts it equals `FEATURE_VERSION`.
- **Out of scope:** the Java scorer and the Java side of the parity test (stage 1b-1); real data.
- **Acceptance criteria:**
  - [x] Failing test first: "committed fixture equals regenerated fixture" (fails: no fixture).
  - [x] Fixture contains every planted case, asserted by name in a test.
  - [x] `FEATURE_VERSION` test fails if either file is changed alone.
  - [x] Fixture files are synthetic and under 200 KB.
- **Verification:** `cd calibration && ruff check . && ruff format --check . && python -m pytest -q`.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-03: produce the Python side of the Java/Python parity test, and guard FEATURE_VERSION.

  Context: stage 1b-1 will implement daily scoring in Java and must match the Python pipeline
  (rules in docs/adr/0008-scoring-contract.md, contract keys in calibration/contract.schema.json).
  It needs a committed fixture: input day_features rows plus the exact expected outputs.

  Do: add a deterministic generator in calibration/ that builds a synthetic day_features table
  with planted edge cases (a missing calendar day; null background_load; a low-coverage day
  (<600 monitored minutes); null sleep_minutes; a null-HRV day; predictions within 0.5 of the
  33/34 and 66/67 band edges; raw predictions outside 0–100 and 0–21), runs pipeline.run, and
  writes backend/src/test/resources/parity/<form>/day_features.csv and expected.json for both
  strain forms ("load" and "zones"). expected.json holds the coefficients and, per day, every
  intermediate (strain, prev_strain, shortfall, sleep need, sleep performance, recovery unrounded
  and rounded, band, per-term contributions, null reasons). Add a test that fails when the
  committed files differ from a fresh generation, and a test asserting
  backend/src/main/resources/application.yml app.metrics.feature-version equals FEATURE_VERSION.

  Constraints: synthetic data only; no Java code; do not change pipeline behaviour.
  Write the "fixture is up to date" test first and watch it fail.

  Verify: cd calibration && ruff check . && ruff format --check . && python -m pytest -q
  ```

### R-04: Record when a model was activated and refuse unsafe activations
- **Priority / Type / Size:** P0 / bug / S
- **Status:** CONFIRMED; done 2026-11-15 (`V13__model_activation.sql`; V12 was taken by R-06). m13 keeps a null `activated_at` (coach decision: no invented time) and the Calibration page warns that it uses the old format. Re-activating the ACTIVE version is a no-op. A candidate older than the ACTIVE version is also refused (coach decision, follow-up PR). m15 activated by the coach on 2026-11-15 19:47:54 UTC; m13 retired at the same instant.
- **Problem:** activation only flips a status. The time of activation is not stored, although stage 1b-3 is defined "from its activation date". Activation also accepts a model built on a different feature set from the one the app is currently computing, and (after R-02) should refuse a contract version the scorer does not understand.
- **Evidence:** `MetricsRepository.java:69-74` (two `UPDATE … SET status`); V6:64-80 has no `activated_at`; `MetricsController.java:80-92` checks only `passed` and `RETIRED`.
- **Why it matters:** without the timestamp the holdout window cannot be reconstructed after the fact; activating an `f2` model while the app computes `f3` rows would score nothing, or score the wrong columns.
- **Proposed change:** new migration `V12__model_activation.sql`: `activated_at TIMESTAMPTZ`, `activated_by BIGINT REFERENCES app_user ON DELETE SET NULL`, `retired_at TIMESTAMPTZ`. `activate` sets them; it returns 409 with a plain message when `feature_version ≠ app.metrics.feature-version` or `coefficients.schema_version` is missing/unsupported. Add `activatedAt` to `ModelVersion` and show it on the Calibration page. If a model is already ACTIVE when V12 runs, leave `activated_at` null and let the UI say "activation time unknown".
- **Out of scope:** scoring; holdout logic; editing V6 or V11.
- **Acceptance criteria:**
  - [x] Failing test first in `MetricsIT`: activating sets `activated_at`; the previously active version gets `retired_at`.
  - [x] Test: activation of a model with another `feature_version` → 409.
  - [x] Test: activation of a model without `schema_version` 2 → 409.
  - [x] Calibration page shows the activation date; i18n key added.
- **Verification:** `cd backend && ./gradlew build`; `cd frontend && npm run lint && npm run typecheck && npm run build`. Coach: `docker compose exec db psql -U training -d training -c "SELECT id, version, status, feature_version, passed, activated_at FROM model_version WHERE athlete_id = 1 ORDER BY id DESC LIMIT 5;"` → exactly one `ACTIVE` row with a timestamp after they press Activate.
- **Coach decision needed:** no (but see question 1 in §5: has Activate already been pressed?).
- **Opus prompt:**
  ```text
  Task R-04: store model activation time and guard activation (backend + small UI change).

  Context: POST /api/models/{id}/activate (MetricsController.activate, MetricsRepository.activate)
  only changes status. Stage 1b-3 needs the activation time, and activation must refuse a model
  the daily scorer cannot use.

  Do: add a NEW Flyway migration V12 (never edit V1–V11) adding model_version.activated_at,
  activated_by, retired_at. Set them in activate(). Return 409 with a plain-language message when
  the model's feature_version differs from app.metrics.feature-version, or when
  coefficients.schema_version is absent or not 2. Expose activatedAt in ModelVersion and show it on
  frontend/src/pages/CalibrationPage.tsx (i18n key in en.json).

  Constraints: tests first (extend MetricsIT; watch them fail). Do not implement scoring.

  Done when: the three new tests pass, existing tests pass, the page shows the date.
  Verify: cd backend && ./gradlew build ; cd frontend && npm run lint && npm run typecheck && npm run build
  ```

### R-05: Check and fix day assignment when no night's sleep was recorded
- **Priority / Type / Size:** P0 for the query (minutes), P1 for the fix / bug / M
- **Status:** rule CONFIRMED in code. On the coach's data (2026-11-13) the day-assignment effect is nil (0 of 483 workouts, 2 nights without sleep), so **no fix now**. The pause double count is CONFIRMED (103 of 482 workouts, median 8.3 min) and estimated at well under 0.5 strain per affected day: fix at the next recalibration.
- **Problem:** an activity or an all-day HR sample belongs to the day opened by the last main sleep that ended within the previous **36 hours**. When the watch records no sleep for a night, everything until 36 h after the previous wake-up stays on the previous day: a run at 10:00 the next morning is added to yesterday's load, and today gets nothing until the evening. Whoop, worn separately, splits those days normally. The rule is also implemented twice (SQL view and Java loop), which can drift.
- **Evidence:**
  - `V2__raw.sql:169-170`: `ss.end_at <= a.start_at AND ss.end_at > a.start_at - INTERVAL '36 hours'`.
  - `DayFeatureService.java:100`: `if (end <= p && p - end < 36 * 3600) day = …`.
  - PLAN §4: 719 Garmin sleeps for an export spanning roughly 790 days, so on the order of 70 nights have no sleep record.
  - A second, smaller effect in the same code: an activity's window for excluding background HR is `start_at + duration_s` (`DayFeatureService.java:66-70`), while its load is summed over the whole stream. If `duration_s` is moving time, minutes after a long pause are counted twice. SUSPECTED; second query below.
- **Why it matters:** strain for both days is wrong (one inflated, one deflated) in training and in the test window, and so is `prev_strain`, which feeds recovery. Fixing it changes features, so it needs feature version `f4`, a new calibration and a new activation. Doing that *after* the live holdout has started would reset the holdout.
- **Proposed change:**
  1. Coach runs the queries below. If `moved_to_previous_day` is under ~1% of activities **and** `days_without_garmin_sleep` is under ~10, record the result in PLAN §4 and close this item as "accepted, revisit at next recalibration".
  2. Otherwise: new migration replacing the view `activity_with_cycle` (a view can be replaced in a new migration) so a sleep's day ends at **04:00 local on the following calendar day** [configurable; rationale: after 04:00 with no recorded sleep, the calendar day is the best guess for a new day], and the same rule in `DayFeatureService.backgroundLoad`, implemented once and shared (one Java helper; the view's rule tested against it). Use the stream's first/last timestamp as the activity window when a stream exists. Bump to `f4` in `application.yml` and `calibration/__main__.py`; update ADR 0002.
  Alternative considered: a 24-hour window instead of 36 — rejected: a 07:00 run after a 07:30 wake-up the day before would still be moved back.
- **Out of scope:** changing how Whoop cycles are dated; changing sleep import; any model change beyond the forced recalibration.
- **Acceptance criteria (if the fix is needed):**
  - [ ] Failing test first: with sleeps on days D and D+2 only, an activity at 10:00 on D+1 gets `cycle_date = D+1` (today: D), and monitoring samples at 10:00 on D+1 go to D+1.
  - [ ] Test: an activity at 01:00 after a recorded sleep on D still belongs to D.
  - [ ] Test: view and Java helper agree on a table of 10 timestamps, including both Bucharest DST changes (2026-03-29, 2026-10-25).
  - [ ] Test: a paused activity's post-pause HR is not also counted as background.
  - [ ] `f4` in both places; R-03's version-sync test passes; ADR 0002 updated; PLAN §2 tells the coach to recompute and recalibrate.
- **Verification:** `cd backend && ./gradlew build`; `cd calibration && python -m pytest -q`. Coach, **before** the fix:
  - `docker compose exec db psql -U training -d training -c "SELECT count(*) AS garmin_activities, count(*) FILTER (WHERE cycle_date < calendar_date AND extract(hour FROM start_at AT TIME ZONE 'Europe/Bucharest') >= 6) AS moved_to_previous_day FROM activity_with_cycle WHERE athlete_id = 1 AND source = 'GARMIN' AND calendar_date >= DATE '2026-01-12';"`
  - `docker compose exec db psql -U training -d training -c "SELECT count(*) AS days, count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sleep_session s WHERE s.athlete_id = 1 AND s.source = 'GARMIN' AND NOT s.is_nap AND s.cycle_date = d::date)) AS days_without_garmin_sleep FROM generate_series(DATE '2026-01-12', DATE '2026-11-08', INTERVAL '1 day') d;"`
  - (pause check, may take a minute) `docker compose exec db psql -U training -d training -c "SELECT count(*) AS activities, count(*) FILTER (WHERE span_s > duration_s * 1.1 + 60) AS with_long_pauses FROM (SELECT a.id, a.duration_s, extract(epoch FROM max(s.ts) - min(s.ts)) AS span_s FROM activity a JOIN activity_stream s ON s.activity_id = a.id WHERE a.athlete_id = 1 AND a.source = 'GARMIN' AND a.start_at >= '2026-01-12' GROUP BY a.id, a.duration_s) x;"`
  - (alignment of overnight HRV, never checked so far; expect the highest `r` at `lag = 0`) `docker compose exec db psql -U training -d training -c "SELECT lag, count(*) AS n, round(corr(g.hrv_rmssd_ms::float8, w.hrv_rmssd_ms::float8)::numeric, 2) AS r FROM generate_series(-1, 1) AS lag JOIN daily_wellness_merged g ON g.athlete_id = 1 AND g.source = 'GARMIN' AND g.hrv_rmssd_ms IS NOT NULL JOIN whoop_reference w ON w.athlete_id = 1 AND w.hrv_rmssd_ms IS NOT NULL AND w.cycle_date = g.calendar_date + lag GROUP BY lag ORDER BY lag;"`
  After the fix: recompute, recalibrate, and the first query returns 0 for `moved_to_previous_day`.
- **Coach decision needed:** yes. "On nights when your Garmin recorded no sleep, at what hour should a new day start for counting training? Options: 04:00 (recommended), another hour, or keep today's behaviour." And, if the fix is needed: "This needs one more calibration run before you press Activate. OK?" Recommendation: yes, now rather than during the holdout.
- **Opus prompt:**
  ```text
  Task R-05: fix which day activities and all-day HR belong to when a night has no recorded sleep.

  Before coding, ask me (the coach) for the output of the four read-only queries listed under
  R-05 in docs/REVIEW.md and wait. If moved_to_previous_day is under ~1% of activities and
  days_without_garmin_sleep is under ~10, do NOT change code: record the numbers in docs/PLAN.md §4
  and stop.

  Problem: the view activity_with_cycle (V2__raw.sql) and DayFeatureService.backgroundLoad assign
  an activity/HR sample to the day opened by the last main Garmin sleep that ended within 36 hours.
  With a missing sleep record, a morning run is counted on the previous day. The rule is also
  duplicated in SQL and Java.

  Do: in a NEW migration, replace the view so a sleep's day ends at 04:00 local time on the next
  calendar day ([configurable], with a one-line rationale; use the athlete's timezone), falling back
  to the local calendar date. Apply the same rule in DayFeatureService through one shared helper,
  and test that the view and the helper agree, including on 2026-03-29 and 2026-10-25
  (Europe/Bucharest DST changes). When an activity has a stream, use the stream's first and last
  timestamps as its window for excluding background HR. Bump the feature version to f4 in BOTH
  application.yml (app.metrics.feature-version) and calibration/calibration/__main__.py
  (FEATURE_VERSION). Update ADR 0002 and docs/PLAN.md (the coach must Recompute features and
  recalibrate; any existing model is for f3 and can no longer be activated).

  Constraints: never edit a merged migration. Tests first, watch them fail. Synthetic fixtures only.
  Do not change Whoop cycle dating, importers or model fitting.

  Verify: cd backend && ./gradlew build ; cd calibration && ruff check . && ruff format --check . && python -m pytest -q
  ```

### R-06: Stop freezing unfinished Whoop cycles on import
- **Priority / Type / Size:** P1 / bug / S
- **Status:** CONFIRMED, including on the coach's data (1 open cycle, dated 2026-11-09)
- **Problem:** a Whoop export taken during the day contains the current, unfinished cycle (recovery is final, day strain is still growing). The importer stores it; every later import of the finished cycle hits the same key and is ignored. That day's Whoop strain stays too low forever. Stage 1b-3 asks the coach to re-import "every week or two", so each import would plant one wrong reference day in the holdout.
- **Evidence:** `RawWriter.java:175-188` (`INSERT INTO whoop_reference … ON CONFLICT DO NOTHING`, unique on `(athlete_id, cycle_start)`, V2:121); `WhoopCsvImporter.java:120-151` imports rows without a cycle end. PLAN §3/§4: the export ends 2026-11-08 but the test window ends 2026-11-09, which is what a cycle that started on the evening of the 8th and was still open would look like.
- **Why it matters:** biased strain comparison on exactly the days that decide whether Whoop can be cancelled; one such day is probably already in the 1a test window.
- **Proposed change:** in `WhoopCsvImporter.cycles`, skip rows with a blank `Cycle end time` (count as `skipped`, with one batch warning: "the current, unfinished day was not imported; it will come in with your next export"). Same for the journal rows of that cycle. For rows already stored: new migration deleting `whoop_reference` and `whoop_journal_entry` rows whose cycle has `cycle_end IS NULL` (an unfinished record is not a measurement; same reasoning as V7's in-place correction), so the next import inserts the finished cycle. Note in ADR 0003.
- **Out of scope:** general "update on re-import" for raw tables (R-10); Whoop sleeps and workouts (their keys are start times of finished events).
- **Acceptance criteria:**
  - [ ] Failing test first (`ImportIT`): import a CSV whose last cycle has no end and strain 3.1; import again with the end set and strain 12.4; `whoop_reference` holds 12.4 for that cycle (today: 3.1).
  - [ ] Test: the first import reports the skipped cycle in the batch warnings.
  - [ ] Migration test (pattern: `WhoopCycleDateMigrationIT`): open cycles are removed, closed ones untouched.
- **Verification:** `cd backend && ./gradlew build`. Coach, before merging: `docker compose exec db psql -U training -d training -c "SELECT count(*) FILTER (WHERE cycle_end IS NULL) AS open_cycles, max(cycle_date) FILTER (WHERE cycle_end IS NULL) AS open_cycle_date, (SELECT count(*) FROM (SELECT 1 FROM whoop_reference WHERE athlete_id = 1 GROUP BY cycle_date HAVING count(*) > 1) x) AS dates_with_two_cycles FROM whoop_reference WHERE athlete_id = 1;"` Expected if the suspicion is right: `open_cycles = 1`, `open_cycle_date = 2026-11-09`. `dates_with_two_cycles` should be 0 (if not, report it: `DayFeatureService.java:176-178` silently keeps only the earlier one). After merging and one new Whoop import + Recompute: `open_cycles = 0`.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-06: do not store unfinished Whoop cycles.

  Problem: WhoopCsvImporter.cycles stores the current, still-open cycle from an export (blank
  "Cycle end time", day strain still growing). whoop_reference is unique on (athlete_id,
  cycle_start) with ON CONFLICT DO NOTHING (RawWriter.whoopCycle), so a later export with the
  finished cycle never replaces it. The coach will re-import Whoop every week or two until 2027-02.

  Do: skip cycles (and their journal rows) with no cycle end; count them as skipped and add one
  plain-language batch warning. Add a NEW migration that deletes whoop_reference and
  whoop_journal_entry rows of cycles with cycle_end IS NULL, so the next import inserts the
  finished version. Note the rule in ADR 0003.

  Constraints: test first (ImportIT: open cycle then finished cycle → finished values stored;
  watch it fail), plus a migration test like WhoopCycleDateMigrationIT. Never edit a merged
  migration. Do not change dedup for other tables.

  Verify: cd backend && ./gradlew build
  End the PR with the SQL the coach runs before and after (see R-06 in docs/REVIEW.md).
  ```

### R-07: Fix the live-holdout rules in writing before looking at holdout data
- **Priority / Type / Size:** P1 / design / S
- **Status:** CONFIRMED
- **Problem:** PLAN §3 says the holdout is "the clean go/no-go check" because the parsimony rule was chosen after seeing the test window. The same is true of two more choices: the strain targets (2.0 / 0.80) and the "≥ 600 minutes" filter were set after seeing 1.91 / 0.82. The holdout only stays clean if its rules are fixed before its data is looked at, and today nothing fixes them: no start date, no minimum number of days, no rule for excluded days, and targets live in an editable file. Also, the 1a evidence is thinner than the headline suggests: with about 55 test days, "0 green↔red flips" cannot show a rate under 3% (the 95% upper bound for 0 of 55 is about 5%).
- **Evidence:** ADR 0007 items 3 and 6 (dates 2026-11-13); `targets.json:3-5`; PROMPTS stage 1b-3 "Only days after activation" (but every day after `test_to` is unseen, and the activation time is not stored — R-04).
- **Why it matters:** this is the evidence for cancelling a paid subscription the coach currently trusts. Without pre-set rules the holdout can be bent the same way the test window was, with nobody intending to.
- **Proposed change:** `docs/adr/0009-live-holdout-protocol.md`, written and merged before stage 1b-3 shows any comparison:
  - holdout = days after the ACTIVE model's `test_to` (not the activation date), ending at the last Whoop day;
  - targets copied into the model row at activation (new JSONB column `holdout_targets`, in the R-04 migration or a later one) and never read from `targets.json` afterwards;
  - exclusions decided now: unfinished Whoop cycles (R-06), days without a score and why; low-coverage strain days reported separately as in 1a;
  - minimum evidence: at least 100 recovery days before a "pass" on the green↔red target can be stated (0 of 100 gives an upper bound of about 3%); every rate shown with its interval (Wilson) and n;
  - a naive comparison shown alongside (yesterday's Whoop band as today's guess) so 88% has a reference point;
  - what resets the holdout: any new activation.
- **Out of scope:** building the holdout view (stage 1b-3); changing 1a results.
- **Acceptance criteria:**
  - [ ] ADR 0009 merged; PROMPTS stage 1b-3 references it.
  - [ ] Coach has answered question 3 in §5.
  - [ ] If the column is added here: test that activation copies the current targets into `holdout_targets`.
- **Verification:** `cd backend && ./gradlew build` if the column is added; otherwise documentation only.
- **Coach decision needed:** yes. "How much evidence do you want before cancelling Whoop? Options: (a) decide at the end of the subscription whatever the count; (b) at least 100 compared days and the same targets as today (recommended); (c) stricter targets." Recommendation: (b), with the numbers shown together with their uncertainty.
- **Opus prompt:**
  ```text
  Task R-07: write ADR 0009 "Live holdout protocol" before stage 1b-3 is built.

  Context: docs/PLAN.md §3 and ADR 0007 explain that some choices (the parsimony rule, the revised
  strain targets, the ≥600-minute filter) were made after seeing the test window, so the comparison
  with Whoop on later days is the clean evidence. It stays clean only if its rules are fixed first.

  Write docs/adr/0009-live-holdout-protocol.md covering: window = days after the ACTIVE model's
  test_to; targets frozen at activation (propose a model_version.holdout_targets JSONB column in a
  NEW migration and copy targets on activate, with a test); exclusions (unfinished Whoop cycles,
  days without a score, low-coverage strain days reported separately); minimum evidence (≥100
  recovery days before stating the green-vs-red target is met; show Wilson intervals and n for
  every rate); a naive reference ("yesterday's Whoop band") shown next to ours; any new activation
  restarts the holdout. Update the stage 1b-3 prompt in docs/PROMPTS.md to follow the ADR.

  Ask me (the coach) first: how much evidence before cancelling Whoop? Recommended: at least 100
  compared days with today's targets. Every number is [configurable] with a one-line rationale.

  Constraints: do not build the holdout view; do not edit merged migrations.
  Verify: cd backend && ./gradlew build (only if you add the column)
  ```

### R-08: Make the nightly backup fail loudly and prove it restores
- **Priority / Type / Size:** P1 / bug / S
- **Status:** CONFIRMED (by reading; not executed); **done 2026-11-15** (`deploy/backup/backup.sh`, `deploy/backup/test-backup.sh`, CI job `backup`)
- **Problem:** the backup is `pg_dump | gzip | openssl`. In `sh`, a pipeline's result is the last command's, so if `pg_dump` fails (database down, wrong password, disk full mid-dump) `openssl` still succeeds, the script prints "backup written" and then prunes older files. Fourteen bad nights erase every good backup. Nothing ever tests that a backup can be restored. Uploaded exports are deleted after import (ADR 0006), so this database is the only copy of the coach's data in the system.
- **Evidence:** `deploy/backup/backup.sh:4` `set -eu` (no pipefail), `:25-27` the pipeline, `:29` `prune`; CI only runs `sh -n` on it (`ci.yml`, job `compose`).
- **Why it matters:** silent loss of all health data and model history after a disk or volume failure.
- **Proposed change:** in `backup.sh`: dump to a temporary file first and check `pg_dump`'s exit status (portable in busybox `sh`), then compress and encrypt; refuse to keep a result smaller than a floor (e.g. 10 KB [configurable]); verify by decrypting and `gunzip -t` before renaming; prune only after a verified success; write `last_success` (timestamp, size) to `/backups/STATUS` and print failures to stderr. Add a CI job that starts TimescaleDB, loads a tiny schema, runs `backup.sh now`, decrypts, restores with the README steps into a second database and compares a row count; plus a negative test with a wrong `PGHOST` expecting a non-zero exit and no new file. README: one line telling the coach to copy `backups/` to another disk.
- **Out of scope:** off-site upload; changing the cipher; showing backup status in the UI (can follow in 1b-2 if cheap).
- **Acceptance criteria:**
  - [ ] Failing test first: with an unreachable database, `backup.sh now` currently exits 0 and leaves a file; after the fix it exits non-zero and leaves none.
  - [ ] Round-trip restore test passes in CI.
  - [ ] Existing backups are never pruned by a failed run (test with two pre-existing files and `BACKUP_KEEP=1`).
- **Verification:** `sh -n deploy/backup/backup.sh`; the new CI job. Coach: `docker compose run --rm backup now && ls -la backups | tail -3` → a new file of plausible size (megabytes, not bytes).
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-08: make deploy/backup/backup.sh fail loudly, and test a full restore.

  Problem: the script runs `pg_dump | gzip | openssl enc` under `set -eu`. A failing pg_dump is
  masked by the pipeline, the script reports success, and prune() then deletes older good backups.
  Nothing tests that a backup restores. The database is the only copy of the user's health data.

  Do: check pg_dump's exit status (dump to a temp file, or another approach that works in busybox
  sh); reject implausibly small results; verify the encrypted file decrypts and passes gunzip -t
  before renaming; prune only after a verified success; write a STATUS file with the last success.
  Add a CI job in .github/workflows/ci.yml: start timescale/timescaledb:2.17.2-pg16, create a
  small table, run the script, restore following the README "Backups" steps into a second
  database, compare row counts; and a negative case (unreachable DB → non-zero exit, no new file,
  old files not pruned). Add one README line about copying backups/ to another disk.

  Constraints: write the negative test first and watch it fail. Keep the file format and the
  README restore commands working. No secrets in the repo (CI uses a throwaway passphrase).

  Verify: sh -n deploy/backup/backup.sh ; the new CI job green.
  ```

### R-09: End sessions when a login is removed or its password changes
- **Priority / Type / Size:** P1 / security / S
- **Status:** CONFIRMED (by reading; no test exists either way)
- **Problem:** a session keeps a copy of the user in memory for 30 days and is never checked against the database again. Deleting an athlete's login, or changing a password, does not end existing sessions. ADR 0005 says "revocation is just session invalidation", but no code invalidates anything. Two smaller points in the same area: the API description pages are public, and login attempts are unlimited.
- **Evidence:** `application.yml:27` `timeout: 30d`; `SecurityConfig.java:55-57` (`HttpSessionSecurityContextRepository`, principal stored in the session); `UserRepository.java:48` and `UserController.java` `delete`/`changePassword` touch only the table; `SecurityConfig.java:40` `permitAll` for `/api/openapi/**`, `/api/docs/**`.
- **Why it matters:** from stage 1b-2 athletes get their own Today screen. A removed athlete (or a phone that was lost) keeps reading health data for up to a month. Low risk on a home network today; real once the app is reachable from outside.
- **Proposed change:** add a small filter (or a `SecurityContextRepository` wrapper) that, per request, reloads the user by id and rejects the session when the user is missing, disabled, or its `password_changed_at` is newer than the session's login time. New migration: `app_user.password_changed_at TIMESTAMPTZ`. `changePassword` rotates the current session id and updates the column. Require authentication for the OpenAPI/Swagger paths. Add a simple in-memory per-username/IP delay after 5 failed logins [configurable; rationale: slows guessing without locking the only coach account].
- **Out of scope:** JWT, 2FA, TLS setup (PLAN backlog), role model changes.
- **Acceptance criteria:**
  - [ ] Failing test first (`LoginFlowIT`): log in as an athlete, delete the login as coach, the athlete's next request returns 401 (today: 200).
  - [ ] Test: after a password change, a second existing session of that user gets 401; the session that made the change keeps working.
  - [ ] Test: anonymous `GET /api/openapi` → 401.
  - [ ] Test: the 6th wrong password within the window is delayed/rejected, and a correct login works after the window.
  - [ ] ADR 0005 updated.
- **Verification:** `cd backend && ./gradlew build`.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-09: make login removal and password changes take effect immediately.

  Problem: sessions last 30 days (application.yml) and hold a copy of the user
  (HttpSessionSecurityContextRepository). UserController.delete and changePassword only change the
  table, so a removed athlete login keeps working until the session expires. ADR 0005 claims
  revocation by session invalidation, but nothing does it.

  Do: on each authenticated request, re-check the user by id (exists, enabled, password not
  changed since the session logged in) and answer 401 otherwise. Add app_user.password_changed_at
  in a NEW migration. Rotate the session id on password change. Require authentication for
  /api/openapi/** and /api/docs/** (and the swagger-ui path). Add a small in-memory slowdown after
  5 failed logins per username/IP ([configurable], one-line rationale). Update ADR 0005.

  Constraints: tests first in LoginFlowIT/SecurityIT (deleted login → next request 401; watch it
  fail). Do not change roles, cookies or CSRF handling. Never log passwords or request bodies.

  Verify: cd backend && ./gradlew build
  ```

### R-10: Define how re-fetched Garmin days replace earlier partial ones
- **Priority / Type / Size:** P1 / design / M
- **Status:** CONFIRMED (code paths); impact arrives with stage 1b-4
- **Problem:** raw tables are insert-once. That is right for a one-time export, but a 07:00 sync fetches a day that is still changing (resting HR, stress, Body Battery, sometimes a re-processed sleep). The first version is kept forever and later, better versions are dropped. Where a later version does get in under a different key, readers combine versions wrongly: two sleep rows for one night are added together, and the wellness view takes the maximum of each column rather than the newest.
- **Evidence:**
  - `GarminExportImporter.java:263` daily summary key is the constant `"uds"` + date → the first fetch of a day wins (`RawWriter.java:56`).
  - `GarminExportImporter.java:227` sleep key = `calendarDate` + exact `start_at` (V2:57); a re-processed sleep with a shifted start becomes a second row, and `DayFeatureService.java:134-140` sums all rows per `cycle_date`.
  - `V2__raw.sql:181-188` `daily_wellness_merged` uses `max(...)` per column.
  - PLAN backlog already notes "Re-import doesn't update existing raw rows".
- **Why it matters:** with daily sync, today's recovery inputs can be frozen from a partial morning payload, or a night's sleep counted twice (sleep performance near 100%, recovery too high), with no error anywhere.
- **Proposed change:** an ADR (`0010-raw-revisions.md`) plus implementation before 1b-4 writes any sync code. Recommended rule: raw stays append-only, but a re-fetched day is a **new revision row**, and readers use the newest revision. Concretely: new migration adding `revision_of`/`fetched_at` semantics via `source_id` (e.g. `uds@<fetch date>` for synced summaries) or an explicit `revision INT` in the unique keys of `daily_wellness` and `sleep_session`; replace `daily_wellness_merged` (new migration) to take the newest non-null value per column by `ingested_at`; add a `sleep_session_current` view that keeps, per athlete/source/`cycle_date`, the newest revision of overlapping sleeps, and make `DayFeatureService`, `CoverageService` and `activity_with_cycle` read it. Sync should also re-fetch the previous 2 days [configurable] each morning. Alternative considered: `ON CONFLICT DO UPDATE` — rejected: breaks "raw data is sacred" (SPEC) and loses what a score was computed from.
- **Out of scope:** the sync adapter itself; Whoop cycles (R-06); `monitoring_hr` and `activity_stream` (keyed by timestamp, already safe).
- **Acceptance criteria:**
  - [ ] Failing test first: two Garmin sleeps for the same night (second one starts 4 minutes later, 20 minutes longer) → `day_features.sleep_minutes` equals the second, not the sum.
  - [ ] Test: two daily summaries for one date (resting HR 47 then 45) → features use 45.
  - [ ] Test: re-importing the identical bulk export still inserts nothing and changes no feature (idempotency kept).
  - [ ] ADR 0003 amended; ADR 0010 added.
- **Verification:** `cd backend && ./gradlew build`. Coach (checks whether overlapping exports already caused double nights): `docker compose exec db psql -U training -d training -c "SELECT count(*) AS nights_with_two_garmin_sleeps FROM (SELECT cycle_date FROM sleep_session WHERE athlete_id = 1 AND source = 'GARMIN' AND NOT is_nap GROUP BY cycle_date HAVING count(*) > 1) x;"` → expected 0 or a small number; a large number means this already affects current features and the item becomes P0.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-10: decide and implement how a re-fetched Garmin day replaces an earlier partial one.
  Must be merged before stage 1b-4 (daily sync).

  Problem: raw tables use INSERT … ON CONFLICT DO NOTHING (RawWriter). A morning sync stores a
  partial daily summary (key: source_id "uds" + date) that later fetches can never improve. A
  re-processed sleep (key: calendarDate + exact start_at) becomes a second row, and
  DayFeatureService sums all sleeps per cycle_date. The view daily_wellness_merged takes max() per
  column instead of the newest value.

  First ask me (the coach) for the output of the query under R-10 in docs/REVIEW.md.

  Do: write docs/adr/0010-raw-revisions.md, then implement: raw stays append-only; a re-fetched
  day is a new revision row; readers use the newest revision. Use NEW migrations only (replace the
  view daily_wellness_merged; add a view of current main sleeps per night; adjust unique keys if
  needed). Make DayFeatureService, CoverageService and activity_with_cycle read the "current"
  views. Keep bulk-export re-import a no-op.

  Constraints: tests first (two sleeps for one night → the newer one counts, not the sum; two
  daily summaries → the newer value; identical re-import → nothing changes). Never edit a merged
  migration. If a feature definition changes for existing data, say so and stop for my go, because
  that needs a FEATURE_VERSION bump in both application.yml and calibration/__main__.py.

  Verify: cd backend && ./gradlew build
  ```

### R-11: Decide how Garmin credentials are stored before writing sync code
- **Priority / Type / Size:** P1 / security / S
- **Status:** CONFIRMED (nothing exists yet)
- **Problem:** stage 1b-4 says "credentials and tokens are encrypted at rest (key from .env)". The pieces that make that meaningful are undecided: whether the password is stored at all (the `garth` library works from long-lived tokens after one login), who decrypts (app or sidecar), how the sidecar is protected from other containers, how multi-factor login is handled, and what the backup contains. The sidecar currently has no authentication and prints request lines.
- **Evidence:** `garmin-sync/garmin_sync/server.py` (plain HTTP, no auth, `log_message` prints the request line); `docker-compose.yml` passes no secret to it; no encryption code or key variable in `.env.example`; PROMPTS stage 1b-4.
- **Why it matters:** a Garmin password unlocks an account with location history and health data. A design chosen in a hurry inside a large sync PR is hard to change after credentials are stored.
- **Proposed change:** `docs/adr/0011-garmin-credentials.md` deciding: store **tokens only**; the password is typed once, passed through to the sidecar and never written; tokens encrypted with AES-256-GCM using `GARMIN_TOKEN_KEY` from `.env` (32 random bytes, generated by a documented command), stored in a new table by the Java app; sidecar is stateless and receives tokens per request over the internal network with a shared secret header; request paths never contain secrets; MFA code entered in the same form; "Disconnect" deletes the row; rotating the key = reconnect; the backup therefore contains only ciphertext, and the key must not be stored with the backups. Add `GARMIN_TOKEN_KEY=` to `.env.example` with a comment, and a startup check that refuses sync (not the app) when the key is missing or short.
- **Out of scope:** implementing sync; choosing the Garmin library version.
- **Acceptance criteria:**
  - [ ] ADR 0011 merged with the coach's consent recorded.
  - [ ] PROMPTS stage 1b-4 references it (see §4).
  - [ ] `.env.example` documents the key; `docker compose config` still passes in CI.
- **Verification:** `cp .env.example .env && docker compose config --quiet`.
- **Coach decision needed:** yes. "To sync every morning the app must be able to act as you on Garmin Connect. Options: (a) you type your Garmin password once; the app keeps only a renewable access token, encrypted (recommended); (b) the app stores your password, encrypted; (c) no automatic sync: you keep uploading exports." Recommendation: (a).
- **Opus prompt:**
  ```text
  Task R-11: write ADR 0011 "Garmin credentials" (design only) before stage 1b-4.

  Decide and document: tokens only, never the password at rest; AES-256-GCM with a key from .env
  (GARMIN_TOKEN_KEY) held by the Java app; a stateless sidecar that receives tokens per request,
  protected by a shared secret on the internal Docker network; MFA handling; disconnect and key
  rotation; what backups contain; what must never be logged (garmin-sync/server.py log_message
  prints request lines today: keep secrets out of URLs). Add GARMIN_TOKEN_KEY to .env.example with
  a comment and the command to generate it.

  Ask me (the coach) for consent first, in plain words, with the three options listed under R-11
  in docs/REVIEW.md, and record my answer in the ADR. Update the stage 1b-4 prompt in
  docs/PROMPTS.md to follow the ADR.

  Constraints: no sync implementation; no credentials or tokens in the repo, tests or logs.
  Verify: cp .env.example .env && docker compose config --quiet
  ```

### R-12: Add timezone/DST tests, a frontend test runner and an image build to CI
- **Priority / Type / Size:** P1 / test-gap / M
- **Status:** CONFIRMED
- **Problem:** three gaps against SPEC "Engineering standards" that the next stages will lean on. (1) No test exercises a daylight-saving change or a non-default timezone for cycle assignment, although every day boundary depends on it; I found no defect by reading, but the 07:00-local scheduler (1b-4) and "today" (1b-2) add more such code. (2) The frontend has no test runner at all; 1b-2 adds logic with a safety meaning ("never show yesterday's recovery as today's"). (3) CI never builds the Docker images, so a broken Dockerfile reaches the coach's machine first (a stale image already cost one iteration, PLAN §3 row m5).
- **Evidence:** no match for `DST`, `2026-03-29` or `2026-10-25` under `backend/src` or `calibration`; `frontend/package.json` has no `test` script; `ci.yml` job `compose` runs only `docker compose config --quiet` and `sh -n`.
- **Why it matters:** a day-boundary slip is the one bug class that already invalidated a full calibration (m1); catching the next one in CI is cheap.
- **Proposed change:**
  - Backend: a parameterised test class for cycle assignment and `TimeParsing` covering 2026-03-29 and 2026-10-25 in Europe/Bucharest, a sleep ending inside the repeated hour, an activity between 00:00 and wake-up, and one athlete in another zone (e.g. America/New_York). Extend `Fixtures` only with synthetic data.
  - Frontend: add Vitest + Testing Library, an `npm test` script, one example test of an existing pure function (`pace.ts`), and run it in the `frontend` CI job. Add `npm test` to PLAN §7.
  - CI: `docker compose build` for `app`, `web`, `garmin-sync`, `calibration`, `backup` (with layer caching), on pull requests.
- **Out of scope:** end-to-end browser tests; property-based testing library (needed in stage 3-2, add it there); coverage thresholds.
- **Acceptance criteria:**
  - [ ] New backend tests pass (they should pass today; if one fails, stop and report it as a bug with the failing case).
  - [ ] `npm test` exists and runs in CI.
  - [ ] CI builds all five images.
  - [ ] PLAN §7 lists the new commands.
- **Verification:** `cd backend && ./gradlew build`; `cd frontend && npm run lint && npm run typecheck && npm test && npm run build`; CI green.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-12: close three test/CI gaps before stages 1b-2 and 1b-4.

  1) Backend: add tests for day assignment across daylight-saving changes. Cover Europe/Bucharest
     on 2026-03-29 and 2026-10-25, a sleep that ends inside the repeated hour, an activity between
     midnight and waking, and an athlete in a different timezone. Target: sleep_session.cycle_date
     (GarminExportImporter.sleep), WhoopCsvImporter.cycleDate, the view activity_with_cycle, and
     DayFeatureService.backgroundLoad. These are expected to pass; if one fails, do not "fix" it
     silently: report the case and stop.
  2) Frontend: add Vitest and Testing Library, an "npm test" script, one test for
     frontend/src/pace.ts, and run it in the frontend CI job.
  3) CI: build all Docker images from docker-compose.yml (including the "tools" profile) on pull
     requests.
  Update docs/PLAN.md §7 with the new commands.

  Constraints: synthetic fixtures only; tools/inspect_exports.py stays Python 3.9-compatible
  (do not touch it); no behaviour changes in this PR.

  Verify: cd backend && ./gradlew build ; cd frontend && npm run lint && npm run typecheck && npm test && npm run build
  ```

### R-13: Correct the Phase 2 tables before anything writes to them
- **Priority / Type / Size:** P1 / design / M
- **Status:** CONFIRMED
- **Problem:** the planning tables were laid down in Phase 0 "so later phases only add columns". Read against SPEC Module 3 and stages 2-1 to 2-5, several shapes will force rework once rows exist:
  1. `planned_session` has both `demand` (KEY/SUPPORTING/EASY) and `is_key`: two sources of truth for the guardrail "protect key sessions".
  2. `adaptation_suggestion` points at one session; a SWAP involves two. There is no key preventing the same suggestion being inserted again each time the morning run repeats (SPEC: "every step is idempotent").
  3. A suggestion records `rules_version` and `model_version` but not which settings or signals produced it; stage 2-5 (settings, versioned) is scheduled *after* 2-3 and 2-4, which need those versions from the first suggestion on.
  4. There is nowhere to store daily signals (2-2). The only candidate, `derived_day_metrics.loads`, is keyed by `model_version`, but signals depend on rules/settings, not on the Module 1 model.
  5. Accepting a suggestion edits `planned_session` in place; nothing links the changed session to the suggestion that changed it, or a REPLACED session to its replacement. The 2-4 backtest and "suggestion history per session" need that link.
  6. `subjective_log` is "latest wins" without saying per what (per day? per session?), and has no injury flag distinct from a niggle, which the running-tolerance definition needs ("without a niggle or injury flag").
  7. The signals need running-only load and running time per day; `day_features` has only `run_km`. Adding columns there would force a feature-version bump and a recalibration, restarting the live holdout.
- **Evidence:** `V3__derived_and_planning.sql:67,73` (`demand`, `is_key`); `:83-102` (`adaptation_suggestion`: single `planned_session_id`, no unique key, no settings/signals version); `:5-19`; `V2__raw.sql:139-154` (`subjective_log`); `V6__metrics.sql:42`; PLAN §5 Phase 2 order.
- **Why it matters:** the suggestion log is the training data for stage 4-3 and the audit trail for the coach's decisions. Fields missing from the first accepted suggestion can never be back-filled.
- **Proposed change:** one ADR (`0012-phase2-schema.md`) and one migration, before stage 2-1:
  - drop `planned_session.is_key` (empty table; new migration) and keep `demand`;
  - `adaptation_suggestion`: add `counterpart_session_id`, `settings_version`, `signals_version`, `engine_run_date`, `dedup_key TEXT` with a unique index on `(athlete_id, dedup_key)` where status = 'PENDING'; define `dedup_key` = hash of (session, date, action, rules_version, inputs);
  - `planned_session`: add `changed_by_suggestion_id` and `replaces_session_id`; add table `planned_session_revision` (append-only before/after JSON, who, when, suggestion id);
  - new `day_signals (athlete_id, cycle_date, signals_version, settings_version, values JSONB, PRIMARY KEY (…))`; stop describing `derived_day_metrics.loads` as the home of acute/chronic load;
  - new `settings_version (id, created_at, created_by, values JSONB)`; move the settings *storage* into stage 2-2 and leave only the screen in 2-5;
  - `subjective_log`: add `injury BOOLEAN`, `niggle_resolved BOOLEAN`; define the "latest wins" key as (athlete, log_date, coalesce(activity_id, planned_session_id, 0)) in a view `subjective_current`;
  - rule: Phase 2 inputs are computed in the `adaptation` module from `activity_with_cycle` + `activity_load` + `derived_day_metrics`, never by adding columns to `day_features` while the holdout runs.
- **Out of scope:** any Phase 2 behaviour (forms, signals, rules); UI.
- **Acceptance criteria:**
  - [ ] Failing test first: inserting the same pending suggestion twice violates the unique index (today: two rows).
  - [ ] Migration applies on a database at V11 (and later) with data in the Phase 1 tables; an IT proves it.
  - [ ] ADR 0012 lists each change with its reason; PLAN §5 and PROMPTS 2-1/2-2/2-3/2-5 updated (see §4).
- **Verification:** `cd backend && ./gradlew build`. Coach (confirms the tables are still empty, so nothing is lost): `docker compose exec db psql -U training -d training -c "SELECT (SELECT count(*) FROM plan) AS plans, (SELECT count(*) FROM planned_session) AS sessions, (SELECT count(*) FROM adaptation_suggestion) AS suggestions, (SELECT count(*) FROM subjective_log) AS checkins;"` → all 0.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-13: correct the Phase 2 tables (schema only) before stage 2-1.

  Context: V3__derived_and_planning.sql created plan, plan_week, planned_session,
  session_template and adaptation_suggestion in Phase 0; V2 created subjective_log. They are empty.
  Read SPEC.md "Module 3" and docs/PLAN.md §5 Phase 2, then the list under R-13 in docs/REVIEW.md.

  Do: write docs/adr/0012-phase2-schema.md and ONE new migration that: drops
  planned_session.is_key (demand stays); adds to adaptation_suggestion counterpart_session_id,
  settings_version, signals_version, engine_run_date and a dedup_key with a partial unique index
  for PENDING rows; adds planned_session.changed_by_suggestion_id and replaces_session_id plus an
  append-only planned_session_revision table; creates day_signals and settings_version tables;
  adds subjective_log.injury and niggle_resolved and a view subjective_current that defines
  "latest wins". Record the rule that Phase 2 inputs are never added to day_features (that would
  force a FEATURE_VERSION bump and a recalibration).

  Constraints: never edit a merged migration. Test first: the same pending suggestion inserted
  twice must be rejected (watch it fail). Add a migration IT. No behaviour, no UI.
  First ask me (the coach) to run the query under R-13 confirming the tables are empty.

  Verify: cd backend && ./gradlew build
  ```

### R-14: Build the local golden-file check the spec asks for
- **Priority / Type / Size:** P2 / test-gap / M
- **Status:** CONFIRMED (absent; already in PLAN backlog)
- **Problem:** SPEC requires "golden-file tests on a frozen 60-day sample of real data, so a code change that moves any score fails loudly". Real data cannot be committed, so the check has to run on the coach's machine. Nothing exists yet. Until it does, a refactor of `DayFeatureService` or the scorer can move real scores without any test failing (fixtures are small and regular).
- **Evidence:** PLAN §5 backlog, second bullet; no golden files or commands in the repo.
- **Why it matters:** the live holdout (to 2027-02) assumes the scores are produced the same way every day. A silent change mid-holdout mixes two models in one verdict.
- **Proposed change:** after 1b-1: a CLI mode of the backend (the same runner 1b-1 adds for recompute) with two commands: `golden freeze --athlete 1 --from … --to …` writes a checksum file **inside the database volume or `./backups/golden/`** (git-ignored) containing per-day hashes of `day_features` and `derived_day_metrics` plus the model version; `golden check` recomputes and prints only dates and column names that differ (never values). Document in PLAN §2 as a step after every update that touches `metrics/`. In CI, the same code runs against the synthetic parity fixture (R-03).
- **Out of scope:** committing any real data or hashes; changing scoring.
- **Acceptance criteria:**
  - [ ] Failing test first: on synthetic data, freeze → change one feature value → `check` exits non-zero naming the date and column.
  - [ ] Output contains no measured values.
  - [ ] `backups/golden/` is covered by `.gitignore` (the `backups/` rule already does).
- **Verification:** `cd backend && ./gradlew build`. Coach: `docker compose run --rm app golden check --athlete 1` → "0 differences" after an update that should not change scores.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-14: local golden-file check for real data (after stage 1b-1 is merged).

  Goal (SPEC.md, Engineering standards): a code change that moves any score on a frozen 60-day
  sample must fail loudly. Real data cannot leave the coach's machine, so this is a command the
  coach runs locally.

  Do: add two CLI commands to the backend's command-line mode (added in 1b-1): "golden freeze"
  writes per-day hashes of day_features and derived_day_metrics for a date range, plus the model
  and feature versions, to a git-ignored folder under ./backups/golden/; "golden check" recomputes
  and lists dates and column names that changed. Never print or store measured values. Run the
  same code in tests against the synthetic parity fixture. Document the coach's commands in
  docs/PLAN.md §2.

  Constraints: test first (freeze, alter one value, check fails naming date and column).
  No real data, hashes or exports in the repo.
  Verify: cd backend && ./gradlew build
  ```

### R-15: Bring documents back in line with the code
- **Priority / Type / Size:** P2 / docs / S
- **Status:** CONFIRMED
- **Problem:** small contradictions that will mislead a context-free engineer:
  - ADR 0004 header still says "Whoop: provisional until the real export is inspected" while its body says verified on 2026-11-10; it gives the Garmin range as 2024-11 → 2026-11, PLAN §4 says 2024-09.
  - ADR 0002/SPEC call a day "wake to wake"; the Whoop export's cycle runs sleep onset to sleep onset. Our days are wake to wake; the difference (sleep time) should be stated once.
  - `V2__raw.sql:106` column comment says `cycle_date` is the "local date of cycle_start"; V7 changed the meaning. A merged migration cannot be edited: fix with `COMMENT ON COLUMN` in the next migration that is written anyway.
  - SPEC says every derived row stores `model_version` **and** `rules_version`; `derived_day_metrics` has no `feature_version` either, although its values depend on it. Add `feature_version` in the 1b-1 migration and say in ADR 0003 which table carries which version.
  - PLAN §6 lists the contract as a private function name (`pipeline._strain_coefficients`); point to ADR 0008 and the schema file after R-01/R-02.
  - ADR 0007 item 3 keeps the history of f2/f3 inline; add a three-line "current state" at the top.
- **Evidence:** the files and lines named above.
- **Why it matters:** PROMPTS tells each new session to trust these documents first.
- **Proposed change:** edit the documents; no code. Fold the `COMMENT ON` and `feature_version` column into whichever migration lands next (R-04 or 1b-1).
- **Out of scope:** rewriting ADR history; SPEC.md wording (it is the coach's source document; note deviations in ADRs instead).
- **Acceptance criteria:**
  - [ ] Each bullet above is resolved or explicitly marked "accepted deviation" in an ADR.
  - [ ] PLAN "Last updated" line refreshed.
- **Verification:** none beyond review; if a migration is touched, `cd backend && ./gradlew build`.
- **Coach decision needed:** no.
- **Opus prompt:**
  ```text
  Task R-15: documentation clean-up (no behaviour changes).

  Fix the contradictions listed under R-15 in docs/REVIEW.md: ADR 0004's status header and date
  range; one clear statement of "our day is wake to wake; Whoop's export cycle is sleep onset to
  sleep onset" in ADR 0002; a "current state" summary at the top of ADR 0007; docs/PLAN.md §6
  pointing to ADR 0008 and calibration/contract.schema.json instead of a private function name;
  ADR 0003 stating which derived table carries which version. Do not edit SPEC.md. Do not edit
  merged migrations: the stale column comment in V2 (whoop_reference.cycle_date) is corrected with
  COMMENT ON COLUMN in the next new migration, not here unless you are adding one anyway.

  Verify: read-through; cd backend && ./gradlew build only if a migration was added.
  ```

## 4. Changes to the plan

Proposed edits, not applied.

**docs/PLAN.md §1 (status table), row 1a**
- Before: "One step left for the coach: press **Activate** on the passing version (Calibration tab)."
- After: "**Do not press Activate yet.** First: review items R-01–R-05 (`docs/REVIEW.md`). Then re-run the calibration once (it must reproduce the same model, now with the extra information the daily scoring needs) and activate that version."

**docs/PLAN.md §5, new stage before 1b-1**
- Before: Phase 1b starts at "1b-1 Daily scoring service".
- After: add "**1b-0 Scoring contract and day checks** — R-01 (ADR 0008), R-02 (contract v2), R-03 (parity fixture), R-04 (activation record), R-05 (day-assignment queries; fix only if needed). Done when: the coach's re-run reproduces the PASS model with `schema_version` 2 (SQL in R-02), the R-05 queries are recorded in §4, and the model is activated with a stored activation time."

**docs/PLAN.md §5, stage 1b-1 "Done when"**
- Before: "Parity test passes; the coach recomputes and sees a score per day."
- After: "The parity test passes on the committed fixture for **both** strain forms, including its planted edge days (ADR 0008); on the coach's machine, the number of days with a recovery score equals the number of days with complete inputs (query provided), and every day without one carries a reason; recovery MAE vs Whoop over the 1a test window computed in SQL from `derived_day_metrics` matches the calibration report to the first decimal."

**docs/PLAN.md §5, stage 1b-2 "Done when"**
- Before: "The coach uses it on their phone for a few mornings."
- After: "Frontend tests cover the three states of Today (fresh score, stale data, missing with reason); screenshots at 390 px in light and dark; then the coach uses it on their phone for 5 mornings and reports any morning where the screen and Whoop pointed to different training decisions."

**docs/PLAN.md §5, stage 1b-3**
- Before: "A daily ours-vs-Whoop view for the ACTIVE model from its activation date … Done when: A running go/no-go dashboard; final decision report around 2027-02."
- After: "Prerequisites: R-06 (unfinished Whoop cycles), R-07 (ADR 0009, holdout rules fixed in advance). The view compares days **after the active model's test window**, with targets frozen at activation, counts and uncertainty shown next to every rate. Done when: the view passes its synthetic tests, shows n and intervals, and the decision report states pass / fail / **not enough days yet** according to ADR 0009."

**docs/PLAN.md §5, stage 1b-4**
- Before: starts directly with implementing the adapter.
- After: "Prerequisites: R-10 (how re-fetched days replace partial ones), R-11 (credentials ADR and consent), R-12 (timezone tests). Each morning also re-fetch the previous 2 days. Done when (add): a second sync of the same morning changes no score; a later sync of the same day updates the inputs and the score, and the earlier inputs are still in the raw tables."

**docs/PLAN.md §5, order of 1b stages**
- Before: 1b-1 → 1b-2 → 1b-3 → 1b-4.
- After: 1b-0 → 1b-1 → **1b-3** → 1b-2 → 1b-4. Reason: the holdout clock runs until 2027-02 whether or not the view exists, and 1b-3 is where a scoring mistake would surface; Today/Trends should not be polished on top of unverified scores. (1b-2 and 1b-3 can also run in parallel.)

**docs/PLAN.md §5, Phase 2**
- Before: 2-1 … 2-5 with "2-5 Settings screen" last.
- After: add "**2-0 Phase 2 schema corrections** (R-13)" before 2-1; move the *storage and versioning* of settings into 2-2 ("signals and settings store"); 2-5 becomes "Settings screen (UI only)". Add to 2-2: "ACWR and Foster use a load in TRIMP units (Banister or Edwards daily load), not the 0–21 strain score, which is a log scale and compresses hard days [configurable; coach to confirm]." Add to 2-4 "Done when": "the replay uses, for each day, only rows whose data existed by that morning (no look-ahead test included)."
- Add a standing rule to §5: "While the live holdout runs, do not add or change `day_features` columns: any change means a new feature version, a new calibration and a restarted holdout. Phase 2 inputs live in `day_signals`."

**docs/PLAN.md §5, Backlog**
- Before: "A golden-file check on a frozen 60-day real sample (spec), run locally by the coach".
- After: "→ R-14, scheduled right after 1b-1." And remove "Re-import doesn't update existing raw rows…" in favour of a pointer to R-10.

**docs/PLAN.md §7**
- After "frontend: `npm run lint && npm run typecheck && npm run build`" add `npm test` (once R-12 lands).

**docs/PROMPTS.md, shared preamble** — add four lines the stage prompts currently assume:
- Before: (ends with) "Keep explanations plain: I'm a coach, not a developer."
- After: add
  "- Never edit a merged Flyway migration; add a new one.
  - Feature engineering lives only in Java (`day_features`); Python only fits. Changing a feature means bumping the version in both `application.yml` and `calibration/__main__.py`, and it restarts the live holdout: ask first.
  - `tools/inspect_exports.py` must stay Python 3.9-compatible.
  - Read `docs/REVIEW.md` for open review items that touch your stage."

**docs/PROMPTS.md, "Stage 1a close-out"**
- Before: "In the app: Calibration → the passing version → Activate."
- After: "After R-01–R-05: re-run `docker compose run --build --rm calibration --athlete 1 --test-from 2026-09-10 --test-to 2026-11-09`, run the comparison query from R-02, then Calibration → the new version → Activate."

**docs/PROMPTS.md, Stage 1b-1**
- Before: "Read the ACTIVE model_version.coefficients (contract: calibration/calibration/pipeline.py _strain_coefficients plus the recovery terms, sleep and bands; ADR 0007)."
- After: "Read the ACTIVE model_version.coefficients. The contract is `docs/adr/0008-scoring-contract.md` plus `calibration/contract.schema.json`; refuse any `schema_version` other than 2. Implement scoring as one service used by the REST endpoint, the CLI and (later) the sync, not inside a controller."
- Before: "drift (any input outside the model's training range: store the ranges in the coefficients when the calibration job writes them, so update the job too)."
- After: "drift (any input outside `training_ranges.p01–p99` in the coefficients; already written by the calibration job). Also flag `profile_changed` when the athlete's HRmax/HRrest differ from `feature_config` in the coefficients."
- Before: "Parity test: a synthetic day_features fixture plus coefficients produced by the Python pipeline … Generate the fixture from the Python tests and commit it as a test resource."
- After: "Parity test: use the committed fixtures in `backend/src/test/resources/parity/` (both strain forms). Compare every intermediate in `expected.json`, not only final scores, with a tolerance of 1e-6 on unrounded values and exact equality on rounded values and bands."
- Add: "Add `feature_version` to `derived_day_metrics` in a new migration. Recompute must accept a date range, but must compute over the full day grid first (yesterday's strain and the 7-day shortfall need earlier days)."

**docs/PROMPTS.md, Stage 1b-3** — replace "Only days after activation" with "Follow `docs/adr/0009-live-holdout-protocol.md`: days after the active model's test window; frozen targets; n and intervals next to every rate; unfinished Whoop cycles are never compared."

**docs/PROMPTS.md, Stage 1b-4** — replace "Credentials and tokens are encrypted at rest (key from .env, never logged)" with "Follow `docs/adr/0011-garmin-credentials.md` (tokens only) and `docs/adr/0010-raw-revisions.md` (a re-fetched day is a new revision)."

**docs/PROMPTS.md, Stage 2-2** — add: "Compute signals in the `adaptation` package from `activity_with_cycle`, `activity_load` and `derived_day_metrics`; store them in `day_signals`. Do not add columns to `day_features`. Overnight HRV exists only from 2025-09-17, so readiness-based signals start there; say so in the UI."

## 5. Questions for the coach

1. **Have you already pressed Activate on the passing version?**
   Why it matters: the app does not yet remember *when* a version was activated, and we want one more (identical) calibration run before the live comparison starts.
   Recommended: if not, wait. If yes, tell us the date; nothing is lost, we will activate the re-run version instead.

2. **Please run the four queries under R-05 and the one under R-06, and paste the output.**
   Why it matters: they tell us (a) how many of your workouts were counted on the wrong day because the watch recorded no sleep that night, and (b) whether the last Whoop day in your import was still unfinished. Both decide whether one more calibration is needed before activation.
   Recommended: do this first; it takes two minutes.

3. **How much evidence do you want before cancelling Whoop?**
   Why it matters: the 1a result is encouraging but rests on about 55 days, and some pass marks were set after seeing the numbers. Zero bad "green vs red" days out of 55 cannot rule out a real rate of up to about 5%.
   Recommended: at least 100 compared days after 2026-11-09 with today's pass marks, shown with their uncertainty. That is reachable by mid-February 2027.

4. **If the watch was off all day yesterday, what should this morning's recovery show?**
   Why it matters: yesterday's strain is one input to recovery; without it the calibrated model has no answer.
   Recommended: "not available today, because yesterday's all-day heart rate is missing". Showing a lower-confidence number is possible later, after a new calibration.

5. **On nights when Garmin recorded no sleep, at what hour should a new training day start?**
   Why it matters: see question 2; today such mornings are added to the previous day.
   Recommended: 04:00.

6. **For daily sync, may the app keep a renewable Garmin access token (encrypted), with your password typed once and never stored?**
   Why it matters: this is the consent stage 1b-4 needs, and the answer shapes the design.
   Recommended: yes, token only.

7. **Should "acute vs chronic load" be built from training load (heart-rate minutes) rather than from the 0–21 strain score?**
   Why it matters: strain is a compressed scale (the step from 14 to 18 is much more work than from 4 to 8), so averages and ratios of strain understate hard weeks.
   Recommended: yes; show strain to you, but compute ratios on load.

8. **Where do your backups go besides this Mac?**
   Why it matters: the app deletes uploaded export files after importing them, so the database and its backups are the only copy.
   Recommended: copy the `backups` folder to an external disk or cloud drive weekly (the files are encrypted), and keep the passphrase somewhere else.

## 6. Suggested execution order

One focused PR per line. Items on the same step can run in parallel.

1. **Step A (no code, today):** coach answers §5 questions 1, 2, 4, 5 → inputs for R-01 and R-05.
2. **Step B (parallel):**
   - PR 1: **R-01** (ADR 0008).
   - PR 2: **R-08** (backup). Independent; do early because it protects the only copy of the data.
   - PR 3: **R-06** (unfinished Whoop cycles). Independent.
3. **Step C:** PR 4: **R-05** fix, only if the queries call for it (changes features → `f4`). Must merge before the calibration re-run in step E.
4. **Step D (sequential):** PR 5: **R-02** → PR 6: **R-03** → PR 7: **R-04** (R-04 can overlap with R-03).
5. **Step E (coach):** update, Recompute features, re-run calibration with fixed test dates, run the R-02 comparison query, Activate.
6. **Step F:** stage **1b-1** (uses R-01–R-04). In parallel: PR 8: **R-07** (ADR 0009), PR 9: **R-12** (tests/CI), PR 10: **R-09** (sessions).
7. **Step G:** stage **1b-3**, then **R-14** (golden check); stage **1b-2** in parallel with 1b-3.
8. **Step H (before 1b-4, parallel):** PR 11: **R-11** (credentials ADR), PR 12: **R-10** (raw revisions). Then stage **1b-4**.
9. **Step I (before Phase 2):** PR 13: **R-13** (Phase 2 schema). **R-15** (docs) can ride along with any earlier PR that touches the same files.
