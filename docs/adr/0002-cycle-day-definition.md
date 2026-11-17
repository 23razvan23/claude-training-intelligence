# ADR 0002: What a "day" is

- Status: accepted (Phase 0)

## Context
The spec defines a day as a physiological cycle (wake to wake, as Whoop does), in the athlete's timezone (default Europe/Bucharest), with calendar dates kept alongside. Raw data must stay immutable, yet a cycle boundary depends on sleep data that may arrive after the activities it bounds.

## Decision
- **Our day is wake to wake; Whoop's export cycle runs sleep onset to sleep onset.** Both describe one 24-hour physiological cycle, but they are cut at different moments: Whoop starts a cycle when the athlete falls asleep, we start a day when the athlete wakes up. The only difference is the night's sleep (about 7–8 h), which Whoop puts at the start of its cycle and we put at the start of the day that sleep opens. That is why every Whoop cycle is dated by its wake day (below). This is the one place the difference is stated; SPEC.md's "wake to wake" is meant in our sense.
- **Whoop cycles:** `whoop_reference.cycle_date` = the local date of **waking**, taken from the row's wake onset, or else from cycle start + 12 h. *Corrected 2026-11-10:* the real Whoop export starts a cycle at **sleep onset**, the evening before. The original rule (date of the cycle start) therefore put every Whoop score one day early. The first real calibration exposed this: Whoop strain correlated 0.81 with the next day's Garmin load and −0.17 with the same day. Migration V7 corrected the stored dates, and the calibration report now runs a day-alignment check.
- **Sleep:** `sleep_session.cycle_date` = local date of waking (`end_at`). A night's sleep opens the cycle of the morning it ends.
- **Garmin daily wellness:** Garmin summarises by calendar date. Its `calendar_date` is used as the cycle date, because overnight HRV and resting HR describe the night that ends that morning.
- **Activities:** raw rows store only `calendar_date` (local date of start). The cycle is derived in the view `activity_with_cycle`, using the last non-nap sleep ending before the activity starts (within 36 h), falling back to the calendar date (`cycle_from_calendar = true`). Because it is a view, late-arriving sleep data reassigns activities without mutating raw rows.

## Consequences
- An activity after midnight but before waking belongs to the previous cycle, matching Whoop.
- Phase 1 must read activities through `activity_with_cycle`, never `activity.calendar_date`, when aggregating per cycle.
- The 36 h window is a heuristic **[configurable]** and is easy to change in the view.
