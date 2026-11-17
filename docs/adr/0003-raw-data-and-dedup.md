# ADR 0003: Append-only raw data, dedup keys and activity matching

- Status: accepted (Phase 0)

## Decision
- Raw tables (`daily_wellness`, `sleep_session`, `activity`, `activity_stream`, `whoop_reference`, `whoop_journal_entry`, `subjective_log`) are append-only. All importer writes go through `RawWriter`, using `INSERT … ON CONFLICT DO NOTHING`. Re-running an import inserts nothing and reports every row as a duplicate.
- **Exception: unfinished Whoop cycles are not stored (R-06).** A cycle with no "Cycle end time" is the current day, and its day strain is still growing. Raw rows are never updated on re-import, so storing it would keep the partial value forever. The importer skips such cycles and their journal rows, counts them as skipped and adds one batch warning ("the current, unfinished day was not imported; it will come in with your next export"). The finished cycle is inserted by a later export. Migration V12 removed the open cycles stored before this rule. Whoop sleeps and workouts are unchanged (their keys are start times of finished events).
- **Amended by ADR 0010 (R-10, V17): Garmin revisions.** A Garmin daily wellness or sleep record that comes again with different values (a later export of a day first exported half-finished) is stored as a new row with source id `<id>@<import batch>`. A record identical to a stored version is still a duplicate. Readers use the newest version: `daily_wellness_merged` takes the newest non-null value per column, and the view `sleep_session_current` keeps each Garmin night's newest import.
- Dedup keys ("source ID plus timestamp"):

  | Table | Unique key | source_id used |
  |---|---|---|
  | daily_wellness | athlete, source, source_id, calendar_date | `uds` (daily summary) or `healthStatus` (HRV) |
  | sleep_session | athlete, source, source_id, start_at | Garmin: calendarDate; Whoop: sleep onset ISO instant |
  | activity | athlete, source, source_id, start_at | Garmin: activityId, or `fit:<serial>:<epoch>`; Whoop: workout start ISO instant |
  | activity_stream | activity_id, ts | n/a |
  | whoop_reference | athlete, cycle_start | n/a |
  | whoop_journal_entry | athlete, cycle_start, question | n/a |

- Garmin wellness arrives as several raw rows per day, one per source file. Readers use the view `daily_wellness_merged`.
- **Same workout, two files.** A Garmin activity appears in `summarizedActivities.json` and as a FIT file. Before inserting either, the importer looks for an existing GARMIN activity starting within **120 s [configurable: `app.import.activity-match-tolerance-seconds`]**. A FIT file attaches its stream to that activity; a summary is skipped as a duplicate. In a bulk export, summaries are read before FIT files, so the summary row wins and keeps Garmin's training load.
- Whoop workouts are stored as `source = 'WHOOP'` activities (reference only). They are never merged with Garmin activities.
- Each import batch runs in one transaction: it lands completely or not at all.
- Derived tables can be dropped and recomputed. Which version each carries: `day_features` → `feature_version`; `model_version` → the `feature_version` it was fitted on; `derived_day_metrics` → `model_version` **and** `feature_version` (V14, stage 1b-1; its scoring rules are fixed per model by ADR 0008); `adaptation_suggestion` → `rules_version`.
  - *Accepted deviation from SPEC.md:* the spec asks every derived row to store `model_version` and `rules_version`. `derived_day_metrics` stores `model_version` and `feature_version` instead; the scoring rules are fixed per model (ADR 0008), so the model version already identifies them. `rules_version` belongs to the Phase 2 suggestion engine only.

## Consequences
- Corrections to raw data are new rows (for example in `subjective_log`, where the latest wins), never updates.
- If a FIT file is imported before its summary, for example via manual upload, the summary's training load is not stored. Acceptable for now. Revisit if Phase 1 needs Garmin's load as a feature.
