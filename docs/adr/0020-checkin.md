# ADR 0020: Athlete check-in (stage 2-1b)

- Status: accepted (2026-11-18; coach answers the same day: the coach may log for an athlete, RPE of the day, niggle severity included, seeding from Garmin wanted but not built yet)
- Amended 2026-11-18: D7 is built, see the amendment at the end.
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

## Amendment 2026-11-18: the Garmin seed (D7 built)
The coach saw the real value ranges (from `tools/inspect_exports.py`, PR #47) and confirmed the mapping and the way it runs. D7 above is left as it was written; this section replaces its assumptions with what was seen. Migration `V24__activity_effort.sql`.

### What the coach's export showed (counts only, no dates)
1,367 activity records; 143 have both `workoutRpe` and `workoutFeel`, 1,224 have neither.
- `workoutRpe`: 10×3, 20×46, 30×53, 40×28, 50×10, 60×3. Every value is a multiple of 10, so it is a 0–100 scale in steps of 10.
- `workoutFeel`: 25×1, 50×18, 75×109, 100×15. Four values only.

### A1. Mapping (confirmed by the coach)
- **RPE = `workoutRpe` ÷ 10**, a whole number, so 10 → 1 … 60 → 6. The divisor is **[configurable]** (`app.checkin.garmin-rpe-divisor`, default 10): Garmin's scale is 0–100 in steps of 10 and all 143 real values were multiples of 10.
- An empty value or 0 means "not set": no row. A value off the 10-step (none seen) is rounded half up and kept within 1–10, because the check-in accepts 1–10 and 0 would mean "not set". This rule has never been used on real data.
- **`workoutFeel` is stored raw and not seeded**: the check-in has no counterpart for it. **Assumption, not fact:** it is probably Garmin's five-step "how did it feel" answer (25/50/75/100 seen); the pairs lean the way that reading predicts (the hardest sessions lean towards 50), but nobody has confirmed it.
- Each seed row: scope = the activity, `log_date` = the activity's local date, `source = 'GARMIN_SEED'`, no author, `on_behalf` false. The lists show "from Garmin" (D6).

### A2. Where the values are kept (no re-reading of exports later)
The `activity` rows never kept the summary JSON, so the importer now stores the two fields as received in a new raw table `activity_effort` (one row per activity, insert-only, never updated; V24). It is written **even when the summary is a duplicate** of an activity already stored (same workout within the match tolerance, ADR 0003), so uploading the same Garmin export again fills in the activities already in the database, without a second activity. If the mapping ever changes, the seed can be derived again from this table without another export. `activity_effort` is read by nothing in feature engineering or scoring.

### A3. Idempotent, and manual always wins
- **An activity that already has any check-in row is skipped**, whether an earlier seed or the athlete's own answer. Skipping a manual answer is deliberate: `subjective_current` takes the newest row, so a seed written today would otherwise replace an answer given last week. A manual check-in made later is newer and wins as usual; the seed row stays in the history (append-only).
- The insert repeats the check inside the statement, so two runs at the same time still add one row.
- Consequence: if the mapping is ever changed, rows already seeded are not rewritten. A new mapping would need its own decision (an explicit re-seed that writes newer rows).

### A4. When it runs (coach: both)
- **After every Garmin export import** (`GARMIN_EXPORT`), in the same run as the import, after its data is stored. A failure of the seed never fails the import; it adds a warning and the command can be run instead. The batch's counts show the new `activity_effort` and `subjective_log` rows.
- **On demand:** `docker compose run --rm app garmin-seed --athlete 1`. It prints how many activities carry an effort, how many check-ins it added, how many already had a check-in and how many had no usable RPE.
- **First time:** the 143 existing activities have no `activity_effort` row yet. Upload the Garmin export again on the Imports page (no duplicates are added); the seed runs after it.
- **Not covered:** the Garmin daily sync. Its answer from Garmin's live interface may name these fields differently and I could not check that without data; a synced activity carries no effort until an export arrives. Left out on purpose; a later change can add it.

### Why the live holdout and the golden check are unaffected
`day_features`, `FEATURE_VERSION`, scoring and the tables the golden check hashes are unchanged. The importer's only new writes go to `activity_effort` and `subjective_log`, which nothing in feature engineering reads. In the golden replay (rolled back), deleting a later batch's `activity` rows also removes their `activity_effort` rows by cascade. The golden check still says **0 differences**.

### Tests
`GarminEffortTest` (mapping edges, the command's arguments), `GarminSeedIT` (import stores and seeds; local-date crossing midnight; re-import and re-run add nothing; a re-uploaded export fills in activities stored earlier; a near-identical activity under another id; other athletes untouched; manual before the seed is kept, manual after the seed wins, whole-day and activity are separate scopes).
