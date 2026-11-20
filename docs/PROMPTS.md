# Prompts for each remaining stage

Paste one prompt per new session, in order. Each assumes the previous stage is merged.
Every prompt starts with the **shared preamble**, so a fresh session has the context and
the rules; paste it together with the stage prompt. The stages and their "done when"
criteria are described in [`docs/PLAN.md`](PLAN.md) §5.

Tip: when a stage finishes, ask the session to "update docs/PLAN.md with what was done
and what's next, and open the PR" if it hasn't already.

---

## Shared preamble (paste first, every time)

```text
You are continuing an existing project: a self-hosted training intelligence app for a running coach.

Before anything else, read docs/PLAN.md (status, results, facts about the real data, conventions,
the coach's setup), then SPEC.md (the build spec) and docs/adr/. Follow docs/PLAN.md §7
(branch/PR flow, tests first for bugs, checks before pushing) and the spec's working agreement:

- Start by restating the plan for this stage in under a page (files you will create or change,
  open questions, risks), then STOP and wait for my go.
- Ask instead of assuming. If you must assume, record it in an ADR and flag it in the summary.
- Mark every sports-science number [configurable] with a one-line rationale. Never present a
  heuristic as fact.
- All my data lives only on my Mac. You cannot reach it. When you need facts about my data,
  give me read-only SQL to paste (docker compose exec db psql -U training -d training -c "...")
  and wait for the output. Never ask me for exports or credentials in chat.
- Test fixtures must be synthetic. Commit in small focused steps; open one PR per agreed step,
  ending with what I must run after merging. Update docs/PLAN.md in the same PR.
- Keep explanations plain: I'm a coach, not a developer.
- A PR is NOT done until every CI check on it is green. Check the PR's CI results yourself and
  fix failures before asking me to merge.
- Never edit a merged Flyway migration; add a new one. Before choosing a migration number, look
  at origin/main for the newest merged V-number (another session may have taken the next one).
- Do NOT start other Claude sessions and do not begin any item you were not asked to do. When
  the PR is open and green, stop and tell me.
- Feature engineering lives only in Java (`day_features`); Python only fits. While the live
  holdout runs (to 2027-02-19), Phase 2 inputs never go into `day_features`: they are computed in
  the `adaptation` package and stored in `day_signals` (ADR 0018 D8). Changing a feature means a
  new feature version (in both `application.yml` and `calibration/__main__.py`) and restarts the
  holdout: ask first.
- After any change touching `metrics/` or `ingestion/`, run the golden check (ADR 0017; PLAN §2:
  `docker compose run --rm app golden check --athlete 1`, which I run on my Mac) and tell me to
  expect `0 differences`; in CI the golden test on the synthetic fixture must pass.
- Do not edit SPEC.md (my source document); note deviations in an ADR. Do not rewrite ADR
  history: add a "current state" note or an amendment.
- `tools/inspect_exports.py` must stay Python 3.9-compatible.

---

## Stage 1a close-out (no session needed)

Done: the coach re-ran the calibration with fixed test dates (R-02) and activated **m15-20261115**
on 2026-11-15. For any later model: re-run the calibration, then **Calibration → the new version →
Activate**. Daily scoring (1b-1) and the live holdout (1b-3) use the ACTIVE version, and activating
a different model restarts the holdout count (ADR 0009 H8): ask first.

---

## Stage 1b-1: Daily scoring service

```text
Stage 1b-1: daily scoring service (Phase 1b, Module 1).

Goal: every day with features gets Recovery, Strain and Sleep scores from the ACTIVE calibrated
model, computed in Java, explainable and reproducible.

Scope:
- Read the ACTIVE model_version.coefficients. The contract is docs/adr/0008-scoring-contract.md
  plus calibration/contract.schema.json; refuse any schema_version other than 2. Support both strain
  forms ("load" with background and "zones"), even though "load" is active now.
- Compute from day_features and write derived_day_metrics: recovery_pct, recovery_band, strain,
  sleep_need_min, sleep_performance_pct, per-feature recovery contributions (points), loads, and
  data_quality flags: stale input, low all-day-HR coverage (strain is low confidence), missing
  inputs, and drift (any input outside the model's training range: store the ranges in the
  coefficients when the calibration job writes them, so update the job too).
- Recompute for any date range: a coach-only REST endpoint, plus a CLI (spec engineering
  standard), e.g. a Spring Boot command-line mode runnable with docker compose run.
- Parity test: a synthetic day_features fixture plus coefficients produced by the Python
  pipeline must give the same scores in Java (within rounding). Generate the fixture from the
  Python tests and commit it as a test resource.
- Hook it into "Recompute features" so scores refresh after recompute.

Done when: the parity test passes; on my machine, after Activate and Recompute, every paired day
has a score, and a SQL spot check shows our recovery next to Whoop's for the last two weeks.
Give me that SQL.
```

---

## Stage 1b-2: Today & Trends screens

```text
Stage 1b-2: Today and Trends screens (Phase 1b). Requires 1b-1 merged.

Goal: the 30-second morning check on my phone (SPEC.md "UI / UX").

Scope:
- Today: recovery dial with band colour; strain so far vs a target (state how the target is
  derived and mark it [configurable]); sleep performance and need; recovery contributions in
  plain words ("HRV 8% above your baseline: +6"); a clear banner when data is stale ("today's
  recovery is missing: last sync …"). Never show yesterday's recovery as today's. Strain on
  low-coverage days is labelled low confidence.
- Trends: HRV, resting HR, recovery, strain, sleep; acute vs chronic load; ranges 4 weeks,
  3 months, 12 months; charts readable at phone width.
- Athlete role: athletes see their own Today and Trends.
- i18n keys and dark mode as on the existing screens; no horizontal scroll at 390 px.

Done when: lint, typecheck and build are clean; screenshots at phone width in light and dark;
I use it for a few mornings and report back.
```

---

## Stage 1b-3: Live holdout vs Whoop

```text
Stage 1b-3: live holdout against Whoop (Phase 1b). Requires 1b-1 merged.

Goal: until my Whoop subscription ends (2027-02-19), show daily how our scores compare with
Whoop's on days the ACTIVE model has never seen. This is the clean go/no-go evidence for
cancelling Whoop (see the Phase 1a summary in docs/PLAN.md).

Follow docs/adr/0009-live-holdout-protocol.md exactly (rules H1-H9); its rules were fixed before
any holdout data was seen and must not be changed by this stage:
- Window: days after the active model's test window (holdout_targets.window_from; 2026-11-09 for
  m15) up to the last finished Whoop day. Unfinished Whoop cycles are never compared.
- Pass marks: read only from model_version.holdout_targets (frozen at activation, V15), never from
  targets.json. No way in the UI to edit marks or exclude days.
- Every day in the window counted once: compared, no Whoop, or no score from us with its ADR 0008
  reason. Low-coverage strain days shown separately, without a mark.
- n and the 95% Wilson interval next to every rate; MAE and Spearman with n.
- Verdict: "not enough days yet" below min_recovery_days (90) compared recovery days; then met /
  not met per mark on the measured value, labelled interim until the last Whoop day; "not enough
  days by the rule" if the window ends short.
- The naive baseline (yesterday's Whoop band and recovery as today's guess) next to ours on the
  same days.
- A new activation restarts the count; a retired version's comparison is kept as history.

Scope:
- A Holdout view (coach): per day, ours vs Whoop for recovery (and band), strain and sleep
  performance; cumulative figures (the evidence) and rolling / weekly figures (for drift);
  exclusion counts by reason; the frozen marks and window dates.
- Make it easy for me to keep feeding Whoop data: I will re-import the Whoop export every week
  or two, and take the last one on or after 2027-02-19. Imports are idempotent; check the flow
  and the instructions.
- A one-click "decision report" (static HTML like the calibration report) to keep when the
  subscription ends, stating pass / not met / not enough days by the rule per ADR 0009 H6.

Done when: the view passes synthetic tests for every rule in ADR 0009 (window, exclusions,
Wilson intervals, verdict states, naive baseline, restart on activation), shows n and intervals,
and works on my data after my next Whoop import.
```

---

## Stage 1b-4: Garmin daily sync

```text
Stage 1b-4: Garmin daily sync (Phase 1b). My consent is recorded in ADR 0011 (option a: I type
my Garmin password once; only a renewable token is kept, encrypted).

Read first: docs/adr/0011-garmin-credentials.md (credentials, tokens only) and
docs/adr/0010-raw-revisions.md (a re-fetched day is a new revision). Prerequisites, all done:
R-10 (raw revisions), R-11 (credentials ADR and consent), R-12 (timezone tests).

Goal: today's scores are ready by 08:00 local time without manual imports (SPEC.md Phase 1
"done when").

Scope:
- Implement garmin-sync/garmin_sync/adapter.py with garminconnect/garth (unofficial API: isolate
  it, pin versions, retry with backoff, alert on failure). It fetches daily summary, sleep, HRV,
  activities (plus FIT files) and all-day HR, and hands them to the backend through the existing
  import path (same raw tables, same dedup, idempotent). A day fetched again becomes a revision
  row (ADR 0010). Each morning also re-fetch the previous 2 days [configurable].
- Credentials: follow ADR 0011 exactly. Tokens only, never the password at rest; AES-256-GCM in
  the Java app with GARMIN_TOKEN_KEY; the garmin_credential table (migration in this stage,
  next free V number); a stateless sidecar with the X-Sync-Secret header (GARMIN_SYNC_SECRET);
  MFA code in the same Connect form; RECONNECT_NEEDED when Garmin rejects the token; Disconnect
  deletes the row; key rotation = reconnect; the startup check (sync disabled, app still starts,
  when a key is missing or invalid); nothing secret in URLs or logs (ADR 0011 D9). Add both
  variables to docker-compose.yml as ${VAR:-}. Tests use fake values only.
- Schedule: default 07:00 local [configurable], plus a "Sync now" button. After a sync:
  recompute features and scores for the affected days.
- A "last sync" indicator and a stale-data banner (1b-2) driven by real sync status. Manual FIT
  and export upload remain as the fallback (ADR 0011 D11).
- Network: the sidecar needs outbound access to Garmin from my machine. Document it in the
  README, with the ADR 0011 notes: connect from the Mac itself (no HTTPS yet), keep .env away
  from backup copies, how to disconnect.

Done when: a test with a mocked Garmin API passes; a second sync of the same morning changes no
score; a later sync of the same day updates the inputs and the score, and the earlier inputs are
still in the raw tables; on my machine a morning sync produces today's scores; if a sync fails,
or Garmin needs me to reconnect, the UI says so.

At the end of 1b-4, Phase 1 is complete: stop and give me the phase summary and demo steps.
```

---

## Stage 2-1: Schedules & check-in

```text
Stage 2-1: schedules and athlete check-in (Phase 2, adaptive engine). Read SPEC.md "Module 3".

Goal: the engine needs a schedule to adapt and subjective inputs, before the plan builder exists.

Scope:
- Manual plan entry: create a plan, add or edit planned sessions (date, type, demand
  key/supporting/easy, structure, targets, estimated strain), using the
  plan/plan_week/planned_session tables as corrected by ADR 0018 (V22): `demand` is the only
  key marker (`is_key` is gone); write a planned_session_revision row (before/after JSON, who)
  for every create/edit/delete; prefer status SKIPPED/REPLACED over deleting a session (a delete
  also removes its history). Add migrations as needed; never edit a merged one.
- Spreadsheet import (CSV and XLSX) of an existing schedule, with a preview and clear errors.
  Inspect a sample I provide, or ask me for its column layout first.
- Check-in form (10 seconds, phone): RPE, soreness 1-5, niggle with body location, niggle
  resolved, injury, illness, note → subjective_log (append-only). Read check-ins only through
  the view subjective_current ("latest wins" per athlete, day and scope: planned session, else
  activity, else the whole day; ADR 0018 D6). Athletes log for themselves; the coach sees it.
- Optionally seed history from the Garmin summaries' workoutRpe/workoutFeel (143 activities):
  ask me first.
- A simple Plan screen listing upcoming sessions (the full calendar comes in Phase 3).

Done when: tests cover entry, import validation and check-in; I can enter this week's sessions
and do a check-in on my phone.
```

---

## Stage 2-2: Signals

```text
Stage 2-2: adaptive-engine signals (Phase 2). Requires 2-1.

Goal: compute every signal in SPEC.md "Module 3 → Signals" per athlete per day, explainably.
Read docs/adr/0018-phase2-schema.md first.

Scope:
- The settings store (moved here from 2-5): every [configurable] value of the signals and
  rules as one JSON document in settings_version (append-only; the defaults are version 1;
  ADR 0018 D2). Code reads the current version; nothing hard-codes a threshold.
- Readiness today, with its 3- and 7-day trend (from 1b-1 scores); 7-day sleep debt; EWMA acute
  (7 d) and chronic (28 d) load, both total and running-only, with ACWR; Foster monotony and
  strain; subjective flags; session demand.
- ACWR and Foster monotony/strain use a daily load in TRIMP units (default: the Edwards daily
  load, as the Trends screen does, ADR 0016; Banister as a setting), never the 0-21 strain
  score, which is a log scale and compresses hard days (ADR 0018 D9).
- Compute signals in the `adaptation` package from activity_with_cycle, activity_load and
  derived_day_metrics; store them in day_signals (signals_version + settings_version). Do NOT
  add or change day_features columns or FEATURE_VERSION: that restarts the live holdout
  (ADR 0018 D8). Overnight HRV exists only from 2025-09-17, so readiness-based signals start
  there; say so in the UI.
- Running tolerance exactly as specified: the highest 4-week average weekly running volume in
  the last 12 months without a niggle or injury flag (subjective_current), decayed for time away (e.g. −10% per week
  beyond one week), capped by chronic running load. Show the number with its derivation, and
  allow a coach override (athletes screen).
- Every constant [configurable] with a rationale, in the settings store.
  ACWR is one signal among several, never a gate.
- Unit tests for each formula; a fixture-based test over a synthetic year.

Done when: tests pass; on my data, a SQL or UI spot check of my tolerance and ACWR looks
plausible to me.
```

Status (2026-11-18): done in two PRs (ADR 0021): PR 1 settings store (coach-wide) and load
signals; PR 2 readiness, sleep debt, check-in flags, session demand, running tolerance with a
coach override (V25), Trends and Athletes screens. For 2-3: on the morning of a day, read the
previous day's load signals (today's load is unfinished, ADR 0021 D5); upcoming sessions come
from the plan, not from day_signals.

---

## Stage 2-3: Rules engine & suggestion workflow

```text
Stage 2-3: rules engine and suggestions (Phase 2). Requires 2-2.

Goal: every morning, review the next 7 days and propose changes with reasons; the coach accepts,
edits or rejects each one (SPEC.md "Decision approach", "Starting rules", "Guardrails",
"Output per suggestion").

Scope:
- A deterministic, versioned rules engine (rules_version on every suggestion), with every
  threshold [configurable]. Implement all the starting rules and all the guardrails (never more
  load on a red day; protect key sessions by moving them; hold weekly targets unless recovery
  forces otherwise; when signals conflict, choose the conservative option and say so; race
  week and taper get reductions only).
- One scenario test per rule, written "given (signals) → expect (suggestion + reason)", plus
  guardrail tests.
- adaptation_suggestion rows: action (keep/reduce/swap/replace/move/add), before/after, 1-3
  plain-language reasons with numbers, confidence, inputs snapshot, status, coach note, and
  (ADR 0018) counterpart_session_id for a swap, rules_version, settings_version,
  signals_version and engine_run_date on every row. The database refuses a second PENDING
  suggestion with the same dedup_key (same session(s), date, action, rules and settings
  version); on a re-run with fresher inputs, expire the outdated pending one, then store the
  new one. Accepting sets planned_session.changed_by_suggestion_id (and replaces_session_id
  for a replacement) and writes a planned_session_revision row with the suggestion id.
- UI: pending suggestions on Today and per session in Plan, with one-tap accept, edit or reject;
  accepting changes the plan; every decision is logged.
- Run after each morning sync and score (1b-4), and on demand.

Done when: every starting rule has a passing scenario test; I can act on suggestions in the UI.
```

Status: split in two PRs (ADR 0022). **PR 1** (engine, rules, guardrails, tests, storing suggestions with
re-run expiry, `suggest` command, run after the sync and a check-in) is done in code. **PR 2** remains: accept,
edit and reject (through `PlanService`, setting `changed_by_suggestion_id` / `replaces_session_id` and writing a
revision with the suggestion id), the Today card with health messages, per-session suggestions on Plan, a
"Check the plan now" button, and a Taper checkbox on the session form (`planned_session.is_taper`).

---

## Stage 2-4: Backtest

```text
Stage 2-4: backtest the adaptive engine on my last 12 months (Phase 2). Requires 2-3.

Goal: show, on my real history, which suggestions the engine would have made each day, especially
in the hard weeks I remember (SPEC.md: "the Phase 2 backtest matters most").

Scope:
- Replay day by day using only data available up to that day (no look-ahead). Use my actual
  activities as "the plan" where no plan existed (ask me how to treat them).
- A report (HTML, like the calibration report): suggestions per week, the signals behind them,
  red-flag weeks highlighted, counts per rule.
- Let me mark a sample of suggestions as sensible or not. Store the marks; they become training
  data for stage 4-3.

Done when: the report runs on my data; I review a sample. Then Phase 2 is complete: stop and
give me the phase summary.
```

---

## Stage 2-5: Settings screen (UI only)

```text
Stage 2-5: Settings screen for every [configurable] value (SPEC.md UI table: Settings).

The settings store and its versioning already exist (stage 2-2, settings_version, ADR 0018 D2);
this stage is the screen only.

Scope: list all thresholds (metrics, signals, rules, targets) with the current value, default,
rationale and unit; edit with validation; reset to default; saving adds a new settings_version
row (never edits one), so suggestions record which settings produced them. Coach only. Recompute
or re-run where a change requires it (say so in the UI). Module 1 scoring constants stay frozen
while the live holdout runs (ADR 0008): show them read-only.

Done when: tests cover validation and versioning; I can change a rule threshold and see it
reflected in the next suggestions.
```

---

## Stage 3-1: Methodology as data

```text
Stage 3-1: plan-builder methodology as data (Phase 3, Module 2). Read SPEC.md "Module 2".

Scope: the session library (typed templates: easy, recovery, long, long with fast finish,
threshold/tempo, VO2max intervals, race pace, hills, strides, race, cross-training, rest), each
with structure, target pace/HR, estimated duration and strain; engine rules config (intensity
distribution pyramidal or polarized with ~75-85% easy, progression ≤ 10%, lighter week every
3rd-4th at 70-80%, long-run cap, taper lengths); race-equivalence paces (VDOT-style or Riegel)
shown next to HR zones, recalculated after a race when I approve. All editable without a
redeploy, all [configurable].

Done when: tests pass; I can view and edit the library and the paces for my profile.
```

---

## Stage 3-2: Plan generator & property tests

```text
Stage 3-2: plan generator (Phase 3). Requires 3-1.

Scope: generate plans for the three goal types (get fit; improve at a distance; specific race
counted back from race day), pre-filled from my data (recent volume and longest run over 8 weeks,
recent results, days available, long-run day, max sessions), never starting above my running
tolerance (2-2) and ramping up. Property-based tests: no week over +10% unless overridden; taper
volume always decreasing; long-run cap respected; a lighter week every 3rd-4th week; the
intensity distribution within bounds.

Done when: property tests pass for all three goal types.
```

---

## Stage 3-3: Goal wizard & calendar

```text
Stage 3-3: goal wizard and plan calendar (Phase 3). Requires 3-2.

Scope: the wizard (3 goal types) → preview → generate → edit; week and month calendar with
drag-and-drop sessions; weekly targets vs actuals; suggestion history per session; each session
links to its template, and the adaptive engine (2-3) can adjust it.

Done when: I generate a marathon plan and judge it usable with minor edits. Then Phase 3 is
complete: stop and give me the phase summary.
```

---

## Stage 4-1 / 4-2 / 4-3: Extensions

```text
Stage 4-1: export planned sessions to Garmin as structured workouts (FIT workout files and/or the
Garmin Connect workout API through the sync sidecar). Ask me which route I prefer.
```

```text
Stage 4-2: wrap the PWA with Capacitor for iOS and Android, with push notifications for new
suggestions. Keep the API unchanged; explain the signing and distribution steps I must do myself.
```

```text
Stage 4-3: learn from my logged decisions (accept/edit/reject history and the backtest marks):
propose a model that predicts my decision, evaluate it against the rules engine on held-out
weeks, and keep it advisory only (the coach decides).
```
