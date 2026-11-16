# ADR 0015: The Trends screen: data, load averages, gaps, phone navigation

- Status: accepted (stage 1b-2b, 2026-11-16; coach decisions TR1–TR3 the same day, taking the recommendations)
- Related: ADR 0008 (scoring, **unchanged**), ADR 0012 (what is stored), ADR 0014 (Today), ADR 0005 (roles).

## In plain words
Trends draws stored numbers over time so you can see direction: HRV, resting HR, recovery, strain, sleep, and training load. It only **reads** stored data. It never computes or changes a score, a feature or a model, so the live holdout (ADR 0009) is not affected. No migration, no change to `day_features`, `FEATURE_VERSION` or scoring.

## Decisions

### D1. One read-only endpoint: `GET /api/athletes/{id}/trends`
- The existing scores API has no HRV in ms, resting HR or sleep duration (they live in `day_features`), so one endpoint lines up `day_features` (the ACTIVE model's feature version) with the ACTIVE model's stored scores, by date.
- AccessPolicy applies: an athlete sees only their own; the coach uses the athlete picker.
- It returns days from `today − (history + warm-up)` to today (athlete's timezone). A day with nothing recorded is simply absent, and any value not recorded is `null`. Nothing is filled in or defaulted.
- **Constants [configurable]** (`app.trends`): `history-days` 365 (the longest range) and `warmup-days` 90 (about three 28-day windows, so the load lines are settled on the first visible day).
- `firstHrvDate` is the first day with overnight HRV anywhere in the athlete's data, so the screen can say where HRV starts.

### D2. Gaps are gaps
A day without data is never drawn as zero and never interpolated: lines break at every missing day (HRV, resting HR, recovery, strain, sleep). Isolated points are still drawn as dots. Tapping a gap day reads "No data". Overnight HRV exists only from 2025-09-17 (ADR 0004), and the HRV chart says so with a note and a marker whenever the range reaches back before the first HRV day.

### D3. Acute and chronic load (coach TR1) [configurable]
- Computed on the **Edwards daily load** (`day_features.day_load_edwards`, the load the model uses), not on the 0–21 strain score, which is a compressed log scale (REVIEW §5 question 7).
- **EWMA** with α = 2 / (span + 1): **acute 7 days** (about one training week) and **chronic 28 days** (about one training block). These are conventional choices in the load-monitoring literature, **not proven thresholds**.
- **Unknown vs rest:** a recorded rest day is 0 and pulls the average down. An unknown day (`null`: no usable data) is skipped, not counted as 0. After an unknown stretch, the average fades by the real number of days that passed: a known day `g` days after the previous known day moves it `1 − (1 − α)^g` of the way to the new value (for `g = 1` that is α).
- **Seed and warm-up:** seeded with the first known value; a line is shown only after `span` known days (so chronic needs 28 days with data; the screen says so).
- **Drawing:** a load line is connected across at most **7** unknown days in a row [configurable] (a weekly average changes slowly); a longer hole is a gap. Other charts allow none.
- **Ratio (coach TR2):** acute ÷ chronic for the newest day where both exist, shown as text, labelled "one signal, not a verdict". No coloured safe zone: the commonly quoted 0.8–1.3 band is contested.
- The EWMA is computed in the browser from the endpoint's daily loads (`frontend/src/trends.ts`, unit-tested on a small synthetic series). Display only.

### D4. Low-coverage strain
A strain day flagged `low_coverage` (ADR 0012 D7: under 10 h of all-day HR) is drawn as a hollow marker, and the readout says "lower confidence".

### D5. Charts
Plain SVG (`LineChart`): the app has no charting library, and the screen needs only lines, dots and gaps. The bundle grows by a few kB instead of a typical charting library's ~100 kB. Tap or drag to read a day; arrow keys work too. Ranges (4 weeks, 3 months, 12 months) switch instantly on already-fetched data.

### D6. Phone navigation (coach TR3)
The tab row no longer scrolls sideways: tabs wrap onto a second line, so none are hidden (before: Holdout and Athletes were off-screen with no hint, `docs/screenshots/1b-2a`). Trends is the second tab, after Today.

## Not done here
- Running volume vs tolerance band (SPEC): needs the running tolerance from stage 2-2.
- Romanian text: `ro.json` is still empty; every string is an i18n key.
