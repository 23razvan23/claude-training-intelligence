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
```

---

## Stage 1a close-out (no session needed)

In the app: **Calibration → the passing version → Activate**. Daily scoring (1b-1) and the
live holdout (1b-3) use the ACTIVE version.

---

## Stage 1b-1: Daily scoring service

```text
Stage 1b-1: daily scoring service (Phase 1b, Module 1).

Goal: every day with features gets Recovery, Strain and Sleep scores from the ACTIVE calibrated
model, computed in Java, explainable and reproducible.

Scope:
- Read the ACTIVE model_version.coefficients (contract: calibration/calibration/pipeline.py
  _strain_coefficients plus the recovery terms, sleep and bands; ADR 0007). Support both strain
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
Stage 1b-4: Garmin daily sync (Phase 1b). Ask for my consent before storing my Garmin login.

Goal: today's scores are ready by 08:00 local time without manual imports (SPEC.md Phase 1
"done when").

Scope:
- Implement garmin-sync/garmin_sync/adapter.py with garminconnect/garth (unofficial API: isolate
  it, retry with backoff, alert on failure). It fetches daily summary, sleep, HRV, activities
  (plus FIT files) and all-day HR, and hands them to the backend through the existing import
  path (same raw tables, same dedup, idempotent).
- Credentials and tokens are encrypted at rest (key from .env, never logged). Add a settings
  form for me to enter them, and a "disconnect" option.
- Schedule: default 07:00 local [configurable], plus a "Sync now" button. After a sync:
  recompute features and scores for the affected days.
- A "last sync" indicator and a stale-data banner (1b-2) driven by real sync status. Manual FIT
  and export upload remain as the fallback.
- Network: the sidecar needs outbound access to Garmin from my machine. Document it in the README.

Done when: a test with a mocked Garmin API passes; on my machine a morning sync produces today's
scores; if a sync fails, the UI says so.

At the end of 1b-4, Phase 1 is complete: stop and give me the phase summary and demo steps.
```

---

## Stage 2-1: Schedules & check-in

```text
Stage 2-1: schedules and athlete check-in (Phase 2, adaptive engine). Read SPEC.md "Module 3".

Goal: the engine needs a schedule to adapt and subjective inputs, before the plan builder exists.

Scope:
- Manual plan entry: create a plan, add or edit planned sessions (date, type, demand
  key/supporting/easy, structure, targets, estimated strain), using the existing
  plan/plan_week/planned_session tables (add migrations as needed).
- Spreadsheet import (CSV and XLSX) of an existing schedule, with a preview and clear errors.
  Inspect a sample I provide, or ask me for its column layout first.
- Check-in form (10 seconds, phone): RPE, soreness 1-5, niggle with body location, illness, note
  → subjective_log (append-only, latest wins). Athletes log for themselves; the coach sees it.
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

Scope:
- Readiness today, with its 3- and 7-day trend (from 1b-1 scores); 7-day sleep debt; EWMA acute
  (7 d) and chronic (28 d) load, both total and running-only, with ACWR; Foster monotony and
  strain; subjective flags; session demand.
- Running tolerance exactly as specified: the highest 4-week average weekly running volume in
  the last 12 months without a niggle or injury flag, decayed for time away (e.g. −10% per week
  beyond one week), capped by chronic running load. Show the number with its derivation, and
  allow a coach override (athletes screen).
- Store the signals daily with a version tag. Every constant [configurable] with a rationale.
  ACWR is one signal among several, never a gate.
- Unit tests for each formula; a fixture-based test over a synthetic year.

Done when: tests pass; on my data, a SQL or UI spot check of my tolerance and ACWR looks
plausible to me.
```

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
  plain-language reasons with numbers, confidence, inputs snapshot, status, coach note.
- UI: pending suggestions on Today and per session in Plan, with one-tap accept, edit or reject;
  accepting changes the plan; every decision is logged.
- Run after each morning sync and score (1b-4), and on demand.

Done when: every starting rule has a passing scenario test; I can act on suggestions in the UI.
```

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

## Stage 2-5: Settings screen

```text
Stage 2-5: Settings screen for every [configurable] value (SPEC.md UI table: Settings).

Scope: list all thresholds (metrics, signals, rules, targets) with the current value, default,
rationale and unit; edit with validation; reset to default; versioned, so scores and suggestions
record which settings produced them. Coach only. Recompute or re-run where a change requires it
(say so in the UI).

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
