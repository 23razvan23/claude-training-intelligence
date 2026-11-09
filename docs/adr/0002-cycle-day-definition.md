# ADR 0002: What a "day" is

- Status: accepted (Phase 0)

## Context
The spec defines a day as a physiological cycle (wake to wake, as Whoop does), in the athlete's timezone (default Europe/Bucharest), with calendar dates kept alongside. Raw data must stay immutable, yet a cycle boundary depends on sleep data that may arrive after the activities it bounds.

## Decision
- **Whoop cycles:** `whoop_reference.cycle_date` = local date of the cycle start, in the cycle's own timezone column (falls back to the athlete's zone).
- **Sleep:** `sleep_session.cycle_date` = local date of waking (`end_at`). A night's sleep opens the cycle of the morning it ends.
- **Garmin daily wellness:** Garmin summarises by calendar date. Its `calendar_date` is used as the cycle date, because overnight HRV and resting HR describe the night that ends that morning.
- **Activities:** raw rows store only `calendar_date` (local date of start). The cycle is derived in the view `activity_with_cycle`, using the last non-nap sleep ending before the activity starts (within 36 h), falling back to the calendar date (`cycle_from_calendar = true`). Because it is a view, late-arriving sleep data reassigns activities without mutating raw rows.

## Consequences
- An activity after midnight but before waking belongs to the previous cycle, matching Whoop.
- Phase 1 must read activities through `activity_with_cycle`, never `activity.calendar_date`, when aggregating per cycle.
- The 36 h window is a heuristic **[configurable]** and is easy to change in the view.
