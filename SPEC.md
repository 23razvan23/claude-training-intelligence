# Build Prompt — Training Intelligence App

_Source: Claude Docs "Build Prompt — Training Intelligence App", copied 2026-11-09._

## How to use this prompt

Paste everything below this section into a coding agent (e.g. Claude Code) at the root of an empty repo, or save it as `SPEC.md` and tell the agent to follow it. The agent should work phase by phase (see Delivery phases) and stop for review at the end of each phase.

Defaults marked **[configurable]** are sensible starting values, not settled science; the app must expose them as settings rather than hard-coding them.

---

## Role, mission and product overview

You are a senior full-stack engineer with solid sports-science literacy (endurance training load, HRV, periodization). Build a self-hosted **training intelligence web app** that lets an advanced amateur runner and running coach drop his Whoop subscription, plan training for himself and the athletes he coaches, and adapt that training day by day.

The app has three modules on one shared data layer:

1. **Recovery & Strain (Garmin → Whoop-like metrics).** Turns Garmin data into Recovery (0–100%), Strain (0–21) and Sleep Performance scores, calibrated against ~1 year of paired Whoop + Garmin history.
1. **Plan Builder.** Generates periodized training plans for three goal types: get fit, improve time at a distance, train for a specific race.
1. **Adaptive Training Engine (highest priority).** Suggests changes to the current schedule based on today's recovery, recent and historical strain, and accumulated running volume versus the athlete's running tolerance.

Guiding principles:

- **Explainable over clever.** Every score and suggestion shows its inputs and the reason in plain words.
- **The coach decides.** The app suggests; nothing in a plan changes without the coach accepting it.
- **Raw data is sacred.** Imported data is immutable; every derived number is reproducible and tagged with the model version that produced it.
- **Web first, phone later.** Build a responsive PWA now, and keep the API clean enough for a native app later.

## Users, scope, and non-goals

The app is single-tenant and multi-athlete from day one: one coach account manages several athletes, including himself.

| Role | Can do |
|---|---|
| Coach | Everything: import data, build and edit plans, accept or reject suggestions, tune settings |
| Athlete | See their own dashboard and plan, log RPE, soreness, niggles and illness, comment on sessions |

**In scope:** running as the primary sport; other Garmin activities (cycling, strength, hiking) count toward strain and load but get no plans of their own.

**Non-goals for v1:**

- Cloning Whoop's proprietary algorithms exactly. The target is close agreement on a validated test set.
- Medical or injury diagnosis. Health flags only ever say "consider rest or see a professional".
- Real-time HR streaming, social features, nutrition planning.
- App-store native apps (planned for later, see Delivery phases).

## Architecture and tech stack

Use a modular monolith in Java for the app, plus a small Python job for model calibration. Calibration is batch work that benefits from the Python data stack. Everything that runs daily lives in Java and reads model coefficients as versioned JSON.

```
Sources                      Shared data layer              Modules
Whoop export (one-time) ─┐                                ┌─► M1 Recovery & Strain ─┐
Garmin bulk + daily sync ┼─► ingestion ─► canonical model ┼─► M2 Plan Builder ───────┼─► M3 Adaptive Engine ─► coach accepts ─► plan
Athlete check-in ────────┘                                └──────────────────────────┘
```
_(Diagram "app architecture · 3 sources, 1 data layer, 3 modules" from the source doc, redrawn as text.)_

Scores from Module 1 and the plan from Module 2 both feed the adaptive engine. Its suggestions reach the plan only after the coach accepts them.

| Layer | Choice | Notes |
|---|---|---|
| Backend | Java 21, Spring Boot 3, Gradle | One module per domain: `ingestion`, `metrics`, `planning`, `adaptation`, `athletes` |
| Database | PostgreSQL 16 (+ TimescaleDB for HR streams) | Flyway migrations; raw and derived tables kept separate |
| Calibration | Python 3.12, pandas, scikit-learn, statsmodels | Offline job; writes `model_coefficients_vN.json` + a validation report |
| Garmin sync | Adapter interface; first implementation = Python `garminconnect`/`garth` sidecar | Unofficial API, can break: isolate it, retry, alert, and support manual FIT/ZIP upload as a fallback |
| Frontend | React + TypeScript, Vite, TanStack Query, Recharts or ECharts | Mobile-first PWA; Capacitor later for phone apps |
| Auth | Spring Security, session or JWT, two roles | Coach and Athlete |
| Deploy | Docker Compose (app, db, sync sidecar) | Self-hosted; nightly encrypted DB backups |

Daily pipeline: sync Garmin → normalize → compute day metrics → run the adaptive engine on upcoming sessions → show suggestions on the dashboard. Every step is idempotent and can be re-run for any date range.

## Data ingestion and canonical data model

All three modules read from one canonical model, so build ingestion and the model first.

**Sources**

| Source | How | Used for |
|---|---|---|
| Whoop export (one-time) | CSV upload: physiological cycles, sleeps, workouts, journal | Ground truth for calibrating Module 1 |
| Garmin bulk export | ZIP upload of the account data export (FIT files + JSON summaries) | Backfilling ~1 year of history |
| Garmin daily sync | Sync adapter, scheduled every morning plus on demand | Ongoing data after Whoop is cancelled |
| Manual FIT upload | Drag-and-drop | Fallback when sync breaks |
| Athlete input | In-app form | RPE, soreness (1–5), niggle with body location, illness, notes |

Inspect the real files before writing any parser, and write parsers against their actual columns. Do not assume column names.

**Core entities**

- `athlete`: profile, HRmax, resting HR, LTHR, threshold pace, zones (HR and pace), timezone, injury notes.
- `daily_wellness`: date, resting HR, overnight HRV (RMSSD), HRV status, respiration rate, SpO2, stress, Body Battery, source.
- `sleep_session`: start, end, stage durations, awake time, sleep score, source.
- `activity`: sport, start, duration, distance, elevation, avg/max HR, Garmin training load and training effect, link to its stream.
- `activity_stream`: per-second HR, pace, cadence and power (TimescaleDB hypertable).
- `whoop_reference`: per-cycle Whoop recovery, strain, HRV, RHR, sleep performance, sleep need.
- `derived_day_metrics`: computed recovery, strain, sleep performance, loads, contributing factors, `model_version`.
- `plan`, `plan_week`, `planned_session`, `session_template`.
- `adaptation_suggestion`: session, suggested change, reasons, inputs snapshot, status (pending, accepted, rejected, edited), coach note.
- `subjective_log`: RPE, soreness, niggle, illness per day or session.

**Rules**

- A "day" is a physiological cycle (wake to wake), as Whoop defines it, stored in the athlete's timezone (default Europe/Bucharest). Calendar dates are kept alongside.
- Raw tables are append-only; imports are idempotent and deduplicated on source ID plus timestamp.
- Derived tables can be dropped and recomputed for any date range from raw data plus a model version.

## Module 1: Garmin to Whoop-like metrics

The goal is scores that agree with Whoop closely enough to make the same training decisions, proven on held-out data. An exact clone is not the goal. Build each score from first principles, then calibrate its weights on the paired year.

**Strain (0–21)**

- Per activity, compute heart-rate load from the second-by-second stream using Banister TRIMP (sex-specific constants in athlete settings). Add Edwards zone-TRIMP as an alternative feature.
- Daily strain = activity load plus low-level daily HR load when Garmin provides all-day HR.
- Map load to the 0–21 scale with a saturating log curve fitted to Whoop day strain: `strain = a · ln(1 + b · load)`.

```
\text{TRIMP} = t_{\min} \cdot \Delta HR_{r} \cdot 0.64 \, e^{1.92\,\Delta HR_{r}}, \quad \Delta HR_{r} = \frac{HR_{avg} - HR_{rest}}{HR_{max} - HR_{rest}}
```

**Recovery (0–100%)**

- Features, each relative to the athlete's own baseline (rolling 30-day, minimum 14 days of data): ln(RMSSD) z-score, resting HR deviation, respiration-rate deviation, sleep performance, previous-day strain, and optionally SpO2 and Body Battery.
- Model: a regularized linear or ordinal model with monotonic constraints (higher HRV can never lower recovery). Use a gradient-boosted model only if it clearly beats the linear model on the test set and stays explainable via per-feature contributions.
- Bands **[configurable]**: green 67–100, yellow 34–66, red 0–33.
- Note the known gap: Garmin's overnight HRV is a whole-night average, while Whoop weights the last deep-sleep period. Let the calibration learn this offset rather than hand-tune it.

**Sleep**

- Sleep need = baseline need + extra from previous-day strain + a share of accumulated sleep debt, each coefficient calibrated against Whoop's sleep need.
- Sleep performance = actual sleep ÷ sleep need, capped at 100%.

**Calibration and validation**

1. Align Garmin and Whoop by physiological cycle; report and exclude days with missing or contradictory data.
1. Use a time-based split: train on the first ~10 months, test on the last ~2. Never shuffle randomly.
1. Report per score: MAE, Spearman correlation, Bland–Altman plot, and recovery-band agreement (% of days in the same green/yellow/red band), with a confusion matrix.
1. Initial acceptance targets **[configurable, revisit after the first run]**: strain MAE ≤ 1.5, recovery band agreement ≥ 70%, no green-vs-red disagreements on more than 3% of test days.
1. Produce an HTML calibration report in the app. This is the go/no-go evidence for cancelling Whoop.

**After Whoop is cancelled:** freeze the calibrated model version. Keep scoring against rolling personal baselines. Show a drift warning when the feature distributions move far outside the training range (new watch, illness block, altitude).

## Module 2: Training plan builder

Generate a periodized, editable plan from a goal and the athlete's real history. Encode the coaching rules as data and configuration, not scattered code, so the coach can change his methodology without a redeploy.

**Goal types**

| Goal | Required inputs | Plan shape |
|---|---|---|
| Get fit | Duration (weeks), days per week, target weekly time | Rolling 3+1 blocks of base building; no taper |
| Improve time at a distance | Distance, current best, target time (optional) | Base → build → specific, ending in a time trial or test race |
| Specific race | Race date, distance, target time, course profile (flat/hilly) | Base → build → specific → taper, counted back from race day |

**Common inputs** (pre-filled from imported data, editable): recent weekly volume and longest run (last 8 weeks), recent race or test results, training days available, preferred long-run day, max sessions per week, injury history, other sports.

**Engine rules [configurable]**

- Training paces from a race-equivalence model (VDOT-style or Riegel), shown next to HR zones. Recalculate after a race or time trial when the coach approves.
- Intensity distribution: pyramidal by default, polarized as an option, ~75–85% of running time easy.
- Progression: weekly volume increase ≤ 10%, a lighter week every 3rd or 4th week at ~70–80% volume, long run ≤ ~30% of weekly volume for marathon plans (stricter for shorter goals).
- Taper: 2–3 weeks for a marathon, 7–10 days for half marathon and shorter; keep intensity, cut volume.
- Never start a plan above the athlete's recent tolerance (see Module 3); ramp up to the plan's volume.

**Session library:** typed templates (easy, recovery, long, long with fast finish, threshold/tempo, VO2max intervals, race-pace, hills, strides, race, cross-training, rest), each with structure, target pace/HR, estimated duration and estimated strain.

**Output:** a calendar of weeks and sessions with weekly targets (km, time, hard-session count). Every session is editable by drag-and-drop. Each session links to the template it came from, and Module 3 can adjust it. A later phase exports sessions to Garmin as structured workouts.

## Module 3: Adaptive training engine

This is the most important module, and it must work from Phase 2, before the plan builder exists. It adapts any schedule: a generated plan, a manually entered one, or one imported from a spreadsheet. Every morning it reviews the next 7 days and proposes changes with reasons. The coach accepts, edits or rejects each one.

**Signals**

| Signal | Definition | Why it matters |
|---|---|---|
| Readiness today | Module 1 recovery + band, plus 3- and 7-day trend | Day-to-day capacity for intensity |
| Sleep debt | Accumulated shortfall vs sleep need, last 7 days | Recovery that the score may not show yet |
| Acute vs chronic load | EWMA acute (7-day) and chronic (28-day) load, both total strain and running-only | Spikes relative to what the athlete is used to |
| Running volume vs tolerance | Current 7-day and 3-week running km/time vs the athlete's running tolerance (below) | Musculoskeletal load, which HR-based metrics miss |
| Monotony and strain (Foster) | Weekly mean load ÷ SD, × weekly load | Too many similar days without real easy days |
| Subjective | RPE, soreness, niggles, illness | Overrides physiology when they disagree |
| Session demand | Planned session's estimated strain and type (key / supporting / easy) | What is at stake if it changes |

**Running tolerance [configurable]:** the highest 4-week average of weekly running volume the athlete has sustained in the last 12 months without a niggle or injury flag, decayed by time away (e.g. −10% per week of interrupted training beyond one week), and capped by the chronic running load. Show it as a number with its derivation, and let the coach override it.

**Decision approach:** a deterministic, versioned rules engine first: transparent, testable, every threshold configurable. Treat ACWR as one signal among several, never a hard gate; its value as an injury predictor is disputed in the literature. Log every suggestion with a snapshot of its inputs and the coach's decision, so a learned model can later be trained on real decisions (later phase, not v1).

**Starting rules [configurable]**

| Condition | Suggested action |
|---|---|
| Recovery red + hard session today | Swap with the next easy day, or turn it into easy/rest; keep the key session within the week if possible |
| Recovery yellow 2+ days in a row + hard session | Reduce reps or time at intensity by 20–30%, keep the warm-up and the session's purpose |
| Running volume > tolerance + 10%, or running ACWR > 1.3 | Trim easy runs first, then shorten the long run; never add volume |
| Sleep debt > 1 night's need | No VO2max work today; threshold or easy instead |
| Niggle flagged | Remove intensity and downhill running for that area; offer cross-training; health message if it persists 3+ days |
| Illness flagged | Rest or very easy until symptoms clear, then a graded return over 2–3 days |
| Green recovery + load well under plan for 2+ weeks | Suggest restoring missed volume gradually, never more than one progression step |

**Guardrails:** never suggest more load on a red day. Protect sessions marked key (long run, race-specific workout) by moving them rather than deleting them. Hold weekly targets unless recovery forces otherwise. When signals conflict, choose the conservative option and say so. Race week and taper sessions get only reduction suggestions.

**Output per suggestion:** the action (keep / reduce / swap / replace / move / add), the before and after of the session, 1–3 reasons in plain language with the numbers behind them, a confidence level, and one-tap accept / edit / reject.

## UI / UX

Design mobile-first; the main use is a 30-second check every morning on a phone.

| Screen | Shows |
|---|---|
| Today | Recovery dial with band colour, strain so far vs target, sleep performance, today's session, and any pending suggestion with its reasons and accept / edit / reject |
| Trends | HRV, RHR, recovery, strain, sleep; acute vs chronic load; running volume vs tolerance band; ranges 4 weeks to 12 months |
| Plan | Week and month calendar, drag-and-drop sessions, weekly targets vs actuals, suggestion history per session |
| Plan builder | Goal wizard (3 goal types) → preview → generate → edit |
| Calibration | Module 1 validation report: Garmin-derived vs Whoop scores, error metrics, band agreement |
| Athletes | Coach's athlete switcher, profiles, zones, tolerance overrides |
| Settings | All [configurable] thresholds, with defaults and a reset button |
| Check-in | 10-second form: RPE, soreness, niggle, illness, note |

Keep the UI in English, ready for a Romanian translation (i18n keys from the start). Support dark mode. Charts must stay readable at phone width.

## Engineering standards, testing, validation

- **Tests:** unit tests on every metric formula and rule; golden-file tests on a frozen 60-day sample of real data, so a code change that moves any score fails loudly; property-based tests for plan constraints (e.g. no week exceeds +10% unless overridden, taper volume always decreases).
- **Rules engine tests:** one scenario test per rule, written as given (signals) → expect (suggestion + reason).
- **Reproducibility:** every derived row stores `model_version` and `rules_version`; a CLI command recomputes any date range.
- **Privacy:** this is health data. Self-host only; encrypt Garmin credentials and tokens at rest; never log raw credentials or full payloads; take encrypted backups; offer export and delete for each athlete.
- **Resilience:** the Garmin adapter fails gracefully. Show the last sync time and a clear banner when data is stale; never compute today's recovery from yesterday's data without saying so.
- **Quality:** CI on every push (build, tests, lint); OpenAPI spec for the backend; Architecture Decision Records in `docs/adr/` for each significant choice.
- **Docs:** a README with one-command local setup (`docker compose up`) and a short guide to importing the Whoop and Garmin exports.

## Delivery phases and acceptance criteria

Build in this order. Stop at the end of each phase with a demo and a short written summary, and wait for approval before starting the next one.

1. **Phase 0: Foundation.** Repo, Docker Compose, CI, auth with two roles, athlete profiles, canonical schema.
   - Done when: Whoop CSVs and a Garmin bulk export import cleanly and idempotently, with a data-coverage report (days with complete Garmin + Whoop data).
1. **Phase 1: Recovery & Strain (Module 1).** Feature pipeline, calibration job, scoring service, Today + Trends + Calibration screens.
   - Done when: the calibration report on the held-out months meets the acceptance targets, or explains clearly which score falls short and why. Daily Garmin sync produces today's scores by 08:00 local time.
1. **Phase 2: Adaptive engine (Module 3).** Manual plan entry and spreadsheet import, signals, tolerance, rules engine, suggestion workflow, check-in form.
   - Done when: every starting rule has a passing scenario test, and a backtest replays the last 12 months day by day and shows which suggestions it would have made. The coach reviews a sample of those.
1. **Phase 3: Plan builder (Module 2).** Goal wizard, rules config, session library, calendar editing.
   - Done when: plans for all three goal types pass the property tests, and a generated marathon plan is reviewed and judged usable by the coach with minor edits.
1. **Phase 4: Extensions.** Garmin structured-workout export, Capacitor phone app with push notifications for new suggestions, learning from logged coach decisions.

The Phase 2 backtest matters most. It shows on real history whether the engine would have flagged the hard weeks the coach remembers.

## Working agreement with the agent

- Start by restating the plan for the current phase in under a page: files you will create, open questions, risks. Then wait for a go.
- When data or requirements are ambiguous, ask instead of assuming. If you must assume to keep moving, write the assumption into an ADR and flag it in the phase summary.
- Look at the real export files before writing parsers or models, and show summary stats (date coverage, missing days, value ranges) first.
- Mark every sports-science number you introduce as **[configurable]**, with a one-line rationale. Never present a heuristic as established fact.
- Prefer simple, explainable methods. Add complexity only when the validation numbers show it pays off.
- Keep commits small and focused, with messages that say why. Never commit credentials, personal data exports or `.env` files.
