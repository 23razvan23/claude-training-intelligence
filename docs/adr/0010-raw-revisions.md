# ADR 0010: Raw revisions: how a Garmin day fetched again replaces the earlier version

- Status: accepted (review item R-10, 2026-11-16; the coach chose "R-10 first, then Garmin exports with each Whoop export")
- Amends: ADR 0003 (append-only raw data and dedup keys).
- Related: ADR 0009 (the live holdout needs fresh Garmin days), stage 1b-4 (the morning sync will rely on this).

## In plain words
A Garmin export taken during the day contains that day half-finished: for example a resting heart rate and stress level from the afternoon. Until now, a later export could never correct it, because a day already stored was skipped. A night's sleep that Garmin re-processed with a slightly different start time was even counted twice.

Now the newest version of a Garmin day counts. The older version is still kept in the database, so nothing is ever overwritten. Uploading the same export twice, or an old export again, still adds nothing.

On the coach's data (2026-11-16) this changes no stored number: every Garmin record has one version so far, and no night has two sleeps. The first change will come from the next Garmin export, for 2026-11-09, the only day imported half-finished.

## Decisions

### D1. Raw stays append-only; a changed record is a revision row
When `RawWriter` meets a **Garmin** daily wellness or sleep record that is already stored under its key, it stores the record again **only if its values differ from every stored version of that record**. The new row gets the source id `<original id>@<import batch id>`.
- Values are compared after being cast to the column types, so a value the database rounds (e.g. respiration to one decimal) is compared as stored.
- A record identical to any stored version is a duplicate. So re-importing the same export, or an older one, inserts nothing.
- A sleep whose start moved is already a new row under its own key (the key includes the start time). Its values are new, so nothing more is needed.
- Not covered: Whoop rows (unfinished cycles are not imported at all, ADR 0003/R-06); activities and their streams; all-day heart rate (keyed by timestamp, so new samples simply add up).

### D2. Readers use the newest version
- **Daily wellness** (view `daily_wellness_merged`, replaced in V17): within one record kind (daily summary `uds`, `healthStatus`), each column takes its **newest non-null** value, by import time. A value missing from the newer version keeps the older one. The kinds are then combined as before (max; min for Body Battery low).
- **Sleep** (new view `sleep_session_current`, V17): for each Garmin sleep record (source id = Garmin's `calendarDate`), only the rows of its **newest import** count. Each import is one transaction, so its rows share `ingested_at`. A night Garmin splits into two records in one export therefore still counts whole, as before. Other sources pass through unchanged.
- Day features, the coverage report and `activity_with_cycle` (the cycle a workout belongs to) read `sleep_session_current`.

### D3. Order of uploads
"Newest" means "imported last". An **older** export uploaded after a newer one changes nothing for records it shares with stored versions (D1). Only a record never seen before, which is unlikely in an older export, would be added as the newest. Upload exports in the order they were taken.

### D4. No feature change, so no new feature version
The definitions of the features do not change: a day's inputs are still read from the same columns. With one version per record, which is all data imported up to now, every view returns exactly what it did before. `FEATURE_VERSION` stays `f3`, and the live holdout (ADR 0009) is **not** restarted. A day whose inputs change because a newer export arrived is new data, not a new rule. ADR 0009 H1 already says scores are computed from the data as imported, whenever that is.

## Consequences
- After each Garmin export: import it, then press **Recompute features**. Changed days are rescored.
- The coach checks on their own data that nothing moved (read-only checksums before and after the update, in the PR).
- Stage 1b-4 (the morning sync) can write each morning's fetch the same way. Re-fetching the previous days then corrects them without further changes here.
- Tests: `RawRevisionsIT` (a later summary replaces the partial one; a shifted or re-processed night replaces the first instead of being added; a missing value keeps the older one; identical and older re-imports insert nothing; a split night still counts whole; workouts follow the newest night).
