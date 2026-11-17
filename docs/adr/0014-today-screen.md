# ADR 0014: The Today screen: states, strain target, plain-words contributions

- Status: accepted (stage 1b-2a, 2026-11-16; coach decisions T1–T4 the same day)
- Related: ADR 0008 (scoring rules, C1/C2/S8; **unchanged**), ADR 0012 (what is stored; unchanged), ADR 0005 (roles).

## In plain words
Today is the 30-second morning check on a phone. It only **shows** stored daily scores (`derived_day_metrics`). It never computes or changes a score, a feature or a model. So the live holdout (ADR 0009) is not affected.

## Decisions

### D1. One endpoint decides the state: `GET /api/athletes/{id}/today`
- It is read-only. AccessPolicy applies: an athlete gets only their own Today, and the coach gets anyone's (the athlete picker in the top bar).
- **"Today"** is the calendar date in the athlete's timezone (`athlete.timezone`, default Europe/Bucharest), taken from the server clock. The phone's clock and timezone are not used.
- The states:

  | State | When | Screen |
  |---|---|---|
  | `FRESH` | the ACTIVE model has a row dated today, with a recovery | dial in the band colour, strain so far vs target, sleep, contributions |
  | `MISSING` | a row dated today, without a recovery (ADR 0008 C1) | "Recovery not available today" plus the stored reasons in plain words; strain and sleep still shown |
  | `STALE` | no row dated today (ADR 0008 C2) | banner "No data for today yet. Last data from <date>", then the last scored day under the heading "Last scored day: <date>" and "This is not today's recovery" |
  | `NO_DATA` / `NO_MODEL` | no scored days yet / no ACTIVE model | what to do next |

- **With Garmin sync (updated 2026-11-17, ADR 0015 D9):** a "Last sync" line sits under the title once Garmin is connected. With sync on, the `STALE` body reads "Last data from <date>. Garmin sync is on: today's scores appear after the next successful sync." (without sync it still points to the next imports). A second banner says why today's data is missing, from the newest sync run, and tells apart:
  - **"Garmin needs you to connect again"** (shown on any day, even with data, since the morning sync has stopped);
  - **"Sync failed (<time>)"** with the reason in plain words, or **"Sync failed (<time>); trying again at <time>"**, or **"Syncing with Garmin…"**;
  - **"Synced <time>, but Garmin had no data for today yet"** (the watch hasn't uploaded the night).
  Not connected or not set up: no sync line or banner. If the sync status can't be read, Today shows as before.
- **Never yesterday as today.** The response field `day` only ever holds the row whose date equals today. Any older row can only appear as `lastScored`, which always carries its date. Rows dated after today (a clock problem) are ignored. The page checks the date a second time before drawing the "today" card. Tests cover this on both sides (`TodayViewTest`, `TodayIT`, `TodayPage.test.tsx`).
- Data-quality notes come from the stored flags (ADR 0012 D7):
  - lower confidence on strain (`low_coverage`) and on recovery (`recovery_lower_confidence`, S8);
  - "press Recompute features" (`stale`);
  - profile changed;
  - drift.

### D2. Strain target (coach T1: the proposed personal range) [configurable]
There is no sound target yet. The proper one is the planned session's estimated strain, which arrives in Phase 2. Until then:
- **The range:** percentiles of the athlete's own strain over the **28 days before** the shown day. Low-coverage days are left out, because their strain is partial (S2).
  - Green day: from the median to the 75th percentile.
  - Yellow day: from the 25th percentile to the median.
  - Red day: from 0 to the 25th percentile.
- **Too few days:** with fewer than **14** such days there is no target.
- **No band:** a day without a recovery band has no target.
- **Percentiles:** linear interpolation between ranks (numpy's default). Rounded to 1 decimal for display.
- **Rationale:** "about your usual on green, less on yellow, little on red". It uses no outside numbers. **A heuristic, not established science**, and the screen says so.
- **Settings:** in `application.yml` under `app.today` (window 28, minimum 14, percentile pairs), each with a one-line rationale. They move to the Settings store in Phase 2.

### D3. HRV in percent (coach T2: option A)
- The stored model input is a z-score (`ln_rmssd_z`), not a percentage.
- For the sentence "HRV 54 ms, 8% above your 30-day baseline (50 ms)", the service takes the **stored** `day_features.ln_rmssd` values over the same trailing window that the features use (`baseline-window-days` 30, `baseline-min-days` 14, current day excluded, `Baseline.of`):
  - percent = (e^(ln_rmssd − baseline mean) − 1) × 100;
  - baseline = e^(mean), the geometric mean, in ms.
- This is a display calculation in Java. It reads `day_features` and writes nothing. With too few baseline days, the line falls back to "HRV compared with your 30-day baseline".
- **Assumption (flagged):** `ln_rmssd` is stored to 4 decimals, so the percentage can differ from an exact recomputation in the last digit. Display only.

### D4. Other contributions in plain words
Points are shown as whole numbers with a sign. The lines are sorted by size of effect.

| Input | Wording |
|---|---|
| Resting HR | "Resting HR 3 bpm below your 30-day average" (the stored `rhr_dev`) |
| Breathing rate | "0.4 breaths/min above" (the stored `resp_dev`) |
| Sleep | "Slept 94% of your sleep need" |
| Yesterday's strain | "Yesterday's strain 13.6 (your usual 9.3)". "Usual" is the training-window average stored with the model: sign·center (ADR 0008 S6, signed-center convention). |

### D5. Navigation (coach T3, T4)
- Today is the first tab and the landing page: `/` goes to `/today`, and every login goes to `/today`.
- When data is stale, the last scored day is shown, clearly dated (T4).

## Consequences
- With exports every week or two, `STALE` was the normal state until daily sync (1b-4). With the morning sync, `FRESH` is normal and the banner explains any morning that isn't.
- Changing a scoring rule still follows ADR 0008. This ADR only covers display.
