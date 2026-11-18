# ADR 0020: Athlete check-in (stage 2-1b)

- Status: accepted (2026-11-18; coach answers the same day: the coach may log for an athlete, RPE of the day, niggle severity included, seeding from Garmin wanted but not built yet)
- Related: ADR 0018 D6 (what "latest wins" means; `subjective_current`), ADR 0008 (scoring, **unchanged**), ADR 0017 (golden check). Migration: `V23__checkin.sql`.

## In plain words
The adaptive engine will weigh how the athlete feels against what the watch says. This stage adds the way to say it: a form of about ten seconds on the phone. Every answer is added as a new row; nothing is ever changed or removed, and a correction is simply a newer answer.

## Decisions

### D1. Scope of a check-in
As in ADR 0018 D6: a planned session if the check-in names one, else an activity, else the whole day. The form offers "Whole day" always, a planned session of that day if one exists, and each Garmin activity of that day. A session or activity fixes the date; for the whole day the date defaults to today in the athlete's own timezone and may go back at most **7 days** [configurable, `app.checkin.max-backdate-days`]: a late entry is still honest because each row keeps its own creation time, while a long gap is more likely a wrong date than a late memory. Naming both a session and an activity is refused (one scope per answer). Whoop-only workouts have no check-in of their own: the whole-day scope covers them.

### D2. Append-only, enforced by the database
`subjective_log` gets the same guard as ADR 0018's tables (`refuse_direct_change`): direct UPDATE and DELETE fail; removing an athlete or user, or an activity being removed (its link becomes empty), still work because those run one trigger level deeper. The code has no update or delete at all, and all reads go through `subjective_current`.

**Consequence of "latest wins":** a newer check-in for the same day and scope replaces the older one as a whole, not field by field. To avoid losing answers by accident, the form starts from the answers that already count for that scope, so a correction keeps what was right.

### D3. RPE of the day
RPE is normally "how hard was this session". For the whole-day scope it means **how hard the day felt overall** (coach decision). The form says so under the question.

### D4. Niggles
A niggle has a **body location** from a fixed list (foot, ankle, calf, shin, knee, hamstring, quad, hip, glute, back, other), an optional **severity 1–5**, and can be marked **gone** (`niggle_resolved`). Rules, checked in code and by a database constraint: severity or "gone" need a location; "gone" has no severity. Details such as left or right go in the note (free text is not a location, so the engine can count days per area). Severity is the athlete's own 1–5 impression, not a clinical grade.

### D5. Who may log
Athletes log and read their own. The coach reads every athlete's check-ins (a recent list) and **may log on an athlete's behalf** (coach decision). Such a row has `on_behalf = true` and the coach's `created_by`, and the lists show "by coach", so the engine and the coach can tell a self-report from an entry. A coach logging for their own athlete profile is not "on behalf".

### D6. Where a row came from: `source`
`MANUAL` (the form) or `GARMIN_SEED` (history copied from the Garmin summaries, see D7). Rows from a seed are labelled "from Garmin" in the lists.

### D7. Seeding from Garmin `workoutRpe` / `workoutFeel`: not in this PR
The coach wants it. But `workoutRpe` and `workoutFeel` are **not stored anywhere** (the `activity` table has no column for them; the importer ignores them), so seeding means a change to the Garmin export import, and the mapping is an assumption. I need to see the real value ranges first (a read-only look at the export through `tools/inspect_exports.py`, no free text). Proposed, to be confirmed: Garmin's `workoutRpe` is a 0–100 scale in steps of 10, so RPE = value ÷ 10; `workoutFeel` has no counterpart among the check-in fields, so it would not be stored as a number. Both are **assumptions** until the coach has seen the values. This ADR will get an amendment when it is built; it will be a separate PR so that nothing touching ingestion rides along with the check-in.

## Why the live holdout and the golden check are unaffected
V23 touches only `subjective_log` (a trigger, two columns, a constraint) and recreates the view `subjective_current`. Nothing in feature engineering or scoring reads either; `day_features`, `FEATURE_VERSION`, `derived_day_metrics` and every table the golden check hashes are untouched, so it still prints **0 differences**. The golden replay deletes later-batch rows from `activity` inside a rolled-back transaction: the foreign key from `subjective_log` only empties a link, which the append-only guard lets through (test `checkInsCanNotBeUpdatedOrDeletedButCascadesStillWork`).

## Tests
`CheckinValidatorTest` (every rule), `CheckinIT` (append-only, latest wins per scope, scope ownership, constraint, timezone, access and on-behalf), `checkin.test.ts` and `CheckinPage.test.tsx` (the form).
