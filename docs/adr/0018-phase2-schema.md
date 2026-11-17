# ADR 0018: Phase 2 schema corrections (before anything writes to the planning tables)

- Status: accepted (review item R-13, 2026-11-17; coach decisions P1 and P2 the same day, taking the recommendations)
- Related: ADR 0008 (scoring, **unchanged** and frozen while the holdout runs), ADR 0009 (live holdout), ADR 0016 (Trends: acute/chronic load on daily load), ADR 0017 (golden check). Migration: `V22__phase2_schema.sql`.

## In plain words
The planning tables (plans, sessions, suggestions, check-ins) were laid down in Phase 0, long before we knew exactly what the adaptive engine needs. Read against the spec, some shapes would have forced a rework once real rows existed, and a few things the suggestion log must record from day one were missing. The suggestion log is the audit trail of your decisions and, later, the training data for stage 4-3; a field missing from the first accepted suggestion can never be filled in afterwards.

On 2026-11-17 you confirmed every one of these tables was empty, so the fix costs nothing. This ADR lists each change and why. Nothing here changes a feature, a score, a model or the live holdout.

## Decisions

### D1. One marker for a key session: `demand`
`planned_session` had both `demand` (KEY / SUPPORTING / EASY) and `is_key`. Two sources of truth for the guardrail "protect key sessions" would sooner or later disagree. `is_key` is dropped; a key session is `demand = 'KEY'`. (`session_template.key_by_default` stays: it is the template's suggestion for a new session's demand, not a second marker on a session.)

### D2. Settings are versioned from the first suggestion on: `settings_version`
Every [configurable] threshold the signals and rules use is saved as one JSON document in `settings_version (id, settings, note, created_by, created_at)`. A saved version is **never changed**: changing a setting adds a new row. A database trigger refuses direct UPDATE and DELETE; deletes that follow from removing a user are allowed (see D5 for how).

Storing and versioning settings moves into **stage 2-2** (signals and settings store), because 2-3 and 2-4 need a settings version on their very first row. Stage 2-5 becomes the Settings **screen** only.

**Assumption (flagged):** settings are coach-wide, one methodology for all athletes; the table has no athlete column. Per-athlete values such as a running-tolerance override belong with the athlete (stage 2-2 decides where). If you want different thresholds per athlete, say so before 2-2; adding a nullable `athlete_id` then is cheap.

### D3. A repeated engine run cannot add the same pending suggestion again: `dedup_key`
The spec says every step of the daily pipeline can be re-run. Without a key, each re-run (a second sync, a manual run) would add the same suggestion again.

**Definition (coach decision P1):** two suggestions are *the same* when they have the same
1. planned session (`planned_session_id`),
2. counterpart session (`counterpart_session_id`, the other side of a swap; empty otherwise),
3. date (`for_date`),
4. action (KEEP / REDUCE / SWAP / REPLACE / MOVE / ADD),
5. rules version (`rules_version`),
6. settings version (`settings_version`; empty if none).

`dedup_key` = md5 of those six values joined with `|` (empty values written as `-`, the date as a day number). The **database computes it** (a generated column), so application code cannot compute it differently. A unique index on `(athlete_id, dedup_key)` **for PENDING rows only** refuses a second pending copy. Once you accept, edit or reject a suggestion, the same suggestion may be made again later (e.g. the next morning).

**The inputs are left out on purpose.** If a later sync changes this morning's numbers, the engine does not stack a second suggestion beside the first: stage 2-3 marks the old pending one EXPIRED and stores the new one, with fresh inputs and reasons. The inputs snapshot is still stored with every suggestion; it just doesn't decide sameness. A new signals version also doesn't make a suggestion different (signals feed the rules; a different outcome shows up as a different action, rules or settings version).

Test: `Phase2SchemaIT.theSamePendingSuggestionCannotBeStoredTwice` (written first; it failed on V21 with two rows stored).

### D4. What a suggestion records
New columns on `adaptation_suggestion`:
- `counterpart_session_id`: a SWAP involves two sessions; the second one goes here. If that session is deleted, the link becomes empty (the before/after JSON still describes it), rather than deleting the suggestion from the log.
- `settings_version` (D2) and `signals_version` (the version tag of the signal code, like `rules_version`): together with the existing `rules_version` and `model_version`, everything that produced a suggestion is named.
- `engine_run_date`: the morning the engine ran. `for_date` is the day the suggestion is about; a suggestion about Saturday can be made on Tuesday.

### D5. Which suggestion changed a session, and the session's history
Accepting a suggestion edits the plan. To keep "suggestion history per session" (Plan screen) and the 2-4 backtest honest:
- `planned_session.changed_by_suggestion_id`: the suggestion whose acceptance last changed this session.
- `planned_session.replaces_session_id`: a replacement session points at the session it replaced (which gets status REPLACED).
- New table `planned_session_revision`: one row per change (CREATE / EDIT / DELETE), with the session's `before` and `after` as JSON, who changed it, when, and the suggestion if one caused it. **Append-only:** a trigger refuses direct UPDATE and DELETE.

Deletes that follow from a foreign key (deleting an athlete, a user, or the session itself) are still allowed: the trigger lets through changes made one trigger level deeper (`pg_trigger_depth() > 1`), which is how PostgreSQL runs cascades. So deleting an athlete still removes all their data (spec: export and delete per athlete). **Consequence for 2-1:** deleting a planned session also deletes its history, so the Plan screen should mark sessions SKIPPED or REPLACED rather than delete them.

### D6. Check-ins: injury, a resolved niggle, and what "latest wins" means
- `subjective_log.injury`: separate from a niggle, because running tolerance is defined as volume sustained "without a niggle **or injury** flag".
- `subjective_log.niggle_resolved`: lets an athlete say a niggle is gone, so "niggle persists 3+ days" (starting rules) has a clear end.
- Check-ins stay append-only: a correction is a new row. The view **`subjective_current`** defines which row counts. **Rule (coach decision P2):** per athlete, per day and per *scope*, the newest row wins (ties: the higher id). The scope is the planned session if the check-in names one; otherwise the Garmin activity; otherwise the whole day (`scope_key` = `session:<id>`, `activity:<id>` or `day`). So a check-in for the day and one for that day's session are both current; a second check-in for the same session replaces the first.

### D7. Where daily signals live: `day_signals`
Stage 2-2's signals (readiness trend, sleep debt, acute/chronic load, tolerance, monotony…) depend on the signal code and the settings, **not** on the Module 1 model. So they get their own table: `day_signals (athlete_id, cycle_date, signals_version, settings_version, signals JSONB, computed_at)`, primary key on the first four. Like `derived_day_metrics`, it is derived data: it can be dropped and recomputed for any date range.

`derived_day_metrics.loads` stays as it is: since 1b-1 it holds the strain inputs the scorer used (ADR 0012). It is **no longer** where acute/chronic load will live, despite the Phase 0 comment in V3. (V3 is merged, so the comment stays there; this ADR supersedes it.)

The JSON columns are named `signals` and `settings`, not `values` as REVIEW suggested, because `VALUES` is an SQL keyword.

### D8. Phase 2 inputs are never added to `day_features`
The signals need running-only load and running time per day; `day_features` has only `run_km`. Adding columns to `day_features` would mean a new `FEATURE_VERSION`, a recalibration and a **restarted live holdout** (ADR 0009 H8). So:

**Rule:** Phase 2 inputs are computed in the `adaptation` module from `activity_with_cycle` (per-activity sport, duration, distance on the right cycle day), `activity_load` (per-activity TRIMP) and `derived_day_metrics` (recovery, strain, sleep), and stored in `day_signals`. Never by adding or changing `day_features` columns, at least while the holdout runs (to 2027-02-19). `day_features` remains Module 1's feature set, versioned with the model.

### D9. ACWR and Foster use daily load, not the strain score (for stage 2-2) [configurable]
ACWR (acute ÷ chronic) and Foster monotony and strain are computed on a **daily load in TRIMP units**, not on the 0–21 strain score. Strain is a log scale: the step from 14 to 18 is much more work than from 4 to 8, so averages and ratios of strain understate hard weeks (REVIEW §5 question 7). The Trends screen already does this (ADR 0016 D3, coach decision TR1: Edwards daily load). Default for 2-2: the same Edwards daily load, so Trends and the engine agree; the running-only variant sums the same load over running activities only. Banister TRIMP remains available as a setting. ACWR stays one signal among several, never a gate (spec).

## Why the live holdout and the golden check are unaffected
- V22 touches only `planned_session`, `adaptation_suggestion`, `subjective_log` (all empty) and adds `settings_version`, `planned_session_revision`, `day_signals` and the view `subjective_current`. Nothing in feature engineering or scoring reads any of them.
- `day_features`, `derived_day_metrics`, `activity_load`, `model_version`, the raw tables (`daily_wellness`, `sleep_session`, `activity`, `whoop_reference`, `monitoring_hr`, `activity_stream`) and the views features are built from (`activity_with_cycle`, `daily_wellness_merged`, `sleep_session_current`) are untouched. `FEATURE_VERSION` stays `f3`.
- The golden check (ADR 0017) hashes exactly `day_features`, the ACTIVE model's `derived_day_metrics` and those raw tables, and replays Recompute features, which reads none of the changed tables. So after this update it still prints **0 differences**. (Its replay deletes later-batch rows from `activity` inside a rolled-back transaction; the foreign keys from `subjective_log` and `planned_session` to `activity` only set a link to empty, and no append-only trigger sits on those tables.)

## Consequences
- 2-1 writes sessions with `demand` only, writes a `planned_session_revision` row for every change, prefers SKIPPED/REPLACED over delete, and reads check-ins through `subjective_current`.
- 2-2 creates the first `settings_version` (the defaults) and stores signals in `day_signals`.
- 2-3 fills every new suggestion column; on a re-run it expires an outdated pending suggestion before storing the new one; when a pending suggestion is accepted it sets `changed_by_suggestion_id` and writes the revision.
- 2-5 is the Settings screen only; saving there adds a `settings_version` row.
- Tests: `Phase2SchemaIT` (dedup, links, append-only, signals key, latest wins), `Phase2SchemaMigrationIT` (V22 applies on a database built to V21 holding Phase 1 data, and keeps it).
