# ADR 0013: Live holdout: how ADR 0009 is implemented

- Status: accepted (stage 1b-3, 2026-11-16; the coach approved the plan, Q1 "download only", Q2 "add the log")
- Related: ADR 0009 (the rules H1–H9; **this ADR changes none of them**), ADR 0008 (reasons), ADR 0012 (stored scores).

## In plain words
ADR 0009 fixed the rules for comparing our scores with Whoop's before any day was looked at. This ADR records the practical choices made while building the comparison: where Whoop's numbers are read from, how a day lands in exactly one group, and how a retired model's figures stay frozen. None of them changes a pass mark, the window or the verdict rule.

## Decisions

### D1. Whoop's numbers come from the imported Whoop rows
The comparison reads `whoop_reference` (finished cycles only: `cycle_end` set), not the Whoop copies in `day_features`.
- The imported rows never change (ADR 0003) and are current straight after an import, so the window always ends on the last finished Whoop day in the database (H2).
- When two cycles share a wake day, the later one counts, the same rule `day_features` uses.
- Our scores come only from the version's own rows in `derived_day_metrics` (H1).
- MAE and Spearman use our **unrounded** values, as calibration does (`contributors.recovery_raw` clipped with the model's own recovery clip; `loads.strain`, `loads.sleep_performance`, `loads.sleep_need`). Bands are the stored bands, which come from the unrounded recovery (ADR 0008 S7). The day table shows the rounded values the coach sees elsewhere.

### D2. Days with Whoop data but no score from us yet
A day after our last scored day is counted as **"no score from us yet"** (`NO_SCORE_YET`): ADR 0009 H4's "stale days". This happens after a Whoop import and before **Recompute features**. Pressing it moves those days into the comparison.

Days scored from features older than the latest import (ADR 0012 `stale` flag) are compared, and their count is shown as a marker.

### D3. One group per day, per score
Recovery, strain, sleep performance and sleep need each put every window day into exactly one group, so each has its own n. When several apply, the order is:
1. `NO_WHOOP`: no finished Whoop cycle, or no Whoop value for that score;
2. `NO_SCORE_YET` (D2);
3. our ADR 0008 reason. For recovery this is the first reason in S6 order, with its detail after a "/" (e.g. `NO_STRAIN_YESTERDAY/MISSING_WORKOUT_HR`);
4. `COMPARED`. For strain, `COMPARED_LOW_COVERAGE` instead when all-day HR is below the frozen `strain_min_monitored_minutes` (unknown minutes count as none).

Lower-confidence recovery stays in `COMPARED`, and its count is shown (A2).

### D4. Retired versions are recalculated, not copied
A retired version's comparison uses:
- only its own score rows, which are never rewritten after retirement (ADR 0012 D3);
- only Whoop rows imported up to its `retired_at`.

Both are fixed, so the figures are the same as on the day it was retired (H1), and no snapshot table is needed. Its phase is `RETIRED`. Its verdict is final, or "not enough days by the rule" below 90 compared recovery days.

### D5. Final, interim, and a mark without a value
- **Final** when the version is retired, or the athlete's local date is after the last Whoop day (`app.metrics.holdout-whoop-end`, 2027-02-19), or the window already reaches that day. Before that, a verdict is **interim** (A3).
- If no full-coverage strain day exists when the verdict is due, the strain marks have no measured value and count as **not met**. *(Assumption, flagged for the coach: ADR 0009 does not cover this case. A mark nobody could measure can't be called met.)*

### D6. A log of Recompute features runs (coach, Q2)
`feature_recompute_log` (V16) records every run: when, by whom, the feature version, how many days were recomputed and which model rescored them. The Holdout view marks the runs inside the window (H8).
- This is a new table; no merged migration is edited.
- `FEATURE_VERSION` and `day_features` are untouched.

### D7. The decision report is downloaded, not stored (coach, Q1)
The one-click decision report (stage 1b-3, PR 2) is a self-contained HTML file: no scripts, no remote loads, served as a download with a no-script CSP (`GET /api/athletes/{id}/holdout/report[?version=]`). The backend builds it (`DecisionReport`) from the same figures as the page each time it's asked, so its wording is tested (`DecisionReportTest`). The coach keeps the file taken after the last Whoop export.
- It is in English only: it's a record to keep, and the app's Romanian translation is still empty (PLAN backlog).
- If the comparison has ended but the last Whoop day is more than a day before the subscription end, the report says to import the last export first.

### D8. Display settings
- The drift window is the last **28 days** of the window (`app.metrics.holdout-rolling-days`). It is shown, never used for the verdict.
- Weeks run Monday to Sunday.

### D9. Keeping it fed (stage 1b-3 PR 3)
- Until the Garmin sync (stage 1b-4), the coach repeats one loop every week or two: a Whoop export and a Garmin account export, both imported, then Recompute features (coach decision 2026-11-16, route a). A Garmin day exported half-finished is replaced by a later export (ADR 0010).
- `GET /api/athletes/{id}/holdout/feed` and the "Keeping it fed" panel on the Holdout view show the last finished Whoop day, the last Garmin day (raw daily summaries), the ACTIVE model's last scored day, and whether an import finished after the last Recompute features run. When Garmin is behind Whoop, the panel says that the days in between wait as "no score from us yet".
- `HoldoutFeedIT` runs the loop twice on overlapping synthetic exports. Nothing is stored twice; the formerly unfinished Whoop day joins; the window grows; both runs are marked.
- The last Whoop export is taken on 2027-02-19. Its unfinished cycle for that day is skipped, so the window normally ends on 2027-02-18.

## Consequences
- `GET /api/athletes/{id}/holdout[?version=]`, `/holdout/versions`, `/holdout/report` and `/holdout/feed` are coach-only and read-only. No endpoint edits the marks or leaves a day out (ADR 0009, Consequences).
- The rules are tested on synthetic days in `HoldoutEvaluatorTest` (H2–H8), `WilsonTest` (the H5 examples) and `HoldoutIT` (database, access, restart on activation, retired history, the log).
