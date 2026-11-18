# ADR 0021: The settings store and the adaptive engine's signals (stage 2-2)

- Status: accepted (stage 2-2, 2026-11-18; coach answers the same day: settings coach-wide; "take the recommendations" on the plan's open questions)
- Related: ADR 0018 (D2 `settings_version`, D7 `day_signals`, D8 nothing new in `day_features`, D9 daily load in TRIMP units), ADR 0016 (Trends: the same EWMA), ADR 0019 D3 (default demand per session type), ADR 0020 (check-in, `subjective_current`), ADR 0017 (golden check), ADR 0008 (scoring, **unchanged**).
- Delivered in two PRs: **PR 1** (#48) the settings store and the load signals (D1–D8); **PR 2** readiness, sleep debt, check-in flags, session demand, running tolerance with its override, and the screens (D9–D12, written when PR 2 was built; migration `V24__tolerance_override.sql`).

## In plain words
The adaptive engine (stage 2-3) will decide using a handful of daily numbers ("signals"): how recovered you are and the trend, sleep debt, how your recent load compares with what you are used to, how much you run against what you tolerate, how monotonous the week was, and how you feel. This stage computes them once a day per athlete and stores them, each tagged with the version of the code and of the settings that produced it.

Every number that is a coaching or sports-science choice (a window length, a threshold, a decay) lives in one **settings document**. Saving a change adds a new version; old versions are never changed. So a later suggestion can always say exactly which settings it used. The Settings screen to edit them comes in stage 2-5.

Nothing here changes a feature, a score, the model or the live holdout.

## Decisions

### D1. Settings are coach-wide (coach, 2026-11-18)
One methodology for all athletes: `settings_version` keeps no athlete column (ADR 0018 D2's assumption, now confirmed). Values that really belong to one athlete, such as a running-tolerance override, live with the athlete (PR 2).

### D2. How the store works
- The catalog in code (`adaptation.settings.SettingsCatalog`) lists every [configurable] value: a dotted path (e.g. `signals.load.chronicSpanDays`), its default and a one-line rationale. `GET /api/settings` (coach) shows all three.
- The first time the app needs settings and the table is empty, it saves the defaults as **version 1** (note "Defaults (stage 2-2)", no user).
- Code reads only the **current version** (the newest row), never the defaults directly.
- When an update adds a value to the catalog, the first read after it saves a **new version**: the current values plus the new value at its default, with a note naming what was added. Older versions stay as they were (the database refuses changes, ADR 0018 D2). So every version holds every number that was used with it.
- Two simultaneous first reads cannot both add a version (a database lock).

### D3. The default demand per session type moved into the store
`planning.defaultDemand.<type>` (ADR 0019 D3's values: KEY for long runs, threshold, VO2max, race pace and races; SUPPORTING for hills; EASY for the rest). Entering a session and `GET /api/plan/session-types` now read the current version. `SessionTypes` keeps the list of types and the built-in starting values for version 1. `SessionValidator.check(request)` still works with those built-in values (the spreadsheet import, stage 2-1a PR 2, may call it); `check(request, defaults)` takes the store's values, and `PlanService` uses that. The import should switch to the second form (`DemandDefaults.current()`).

### D4. Daily load: the same number the Trends screen draws [configurable]
ACWR and Foster use a daily load in TRIMP units (ADR 0018 D9): by default **Edwards** (`signals.load.dailyLoad = edwards`), the alternative **banister**. The total comes from `day_features.day_load_edwards` / `day_load_banister`, read only, so the engine and the Trends screen always agree, including which days are rest (0) and which are unknown (nothing recorded: skipped, never counted as 0). The running-only load is the same TRIMP summed over the day's Garmin running activities (`activity_with_cycle` + `activity_load`); on a recorded day without a run it is 0. Running km and minutes come from the same activities. Whoop-only workouts (66 in the history) are invisible here, as in the features.

### D5. Acute and chronic load and ACWR [configurable]
EWMA exactly as Trends computes it (ADR 0016 D3; `Ewma` repeats `trends.ts`'s test cases): α = 2/(span+1), spans **7** (acute) and **28** days (chronic), conventional choices and not proven thresholds; unknown days skipped, the average fading by the real number of days that passed; nothing shown before `span` days with data. **ACWR** = acute ÷ chronic, total and running-only. It is **one signal among several, never a gate** (spec): its value as an injury predictor is disputed. A day's load signals include that day's own load; on the morning of a day, the engine (2-3) should read the previous day's load signals, because today's load is not finished.

### D6. Foster monotony and strain [configurable]
Over the last **7** days including the day (`signals.foster.windowDays`): monotony = mean daily load ÷ its standard deviation; strain = the week's load × monotony (Foster 1998). The standard deviation is the population one, over the recorded days only. No monotony when fewer than **5** of the 7 days are recorded (`minKnownDays`; with fewer days the spread means little) or when every recorded day has the same load (the ratio is undefined, rather than infinite).

### D7. Running volume
Running km and minutes over the last **7** and **21** days including the day (`signals.running.*`, the spec's "current 7-day and 3-week"), plus the 21-day weekly average in km. Tolerance is in **km per week** (coach, 2026-11-18); time is shown next to it.

### D8. What is stored, and when it is computed
- `day_signals`, one row per athlete and day from the first day with features to today (athlete's timezone), with `signals_version` (`s1`; bumped when a formula changes) and the settings version used. JSON: `load` (method, daily, acute, chronic, acwr, running {daily, acute, chronic, acwr}), `foster` (windowDays, knownDays, weekLoad, mean, sd, monotony, strain), `running` (shortWindowDays, shortKm, shortMinutes, longWindowDays, longKm, longMinutes, longWeeklyAvgKm). A missing number is absent or null, never 0.
- A recompute replaces only the rows of the same signals and settings version; rows of older versions stay, so a suggestion that names them can still be reproduced. Like every derived table, it can be dropped and recomputed.
- **When:** after **Recompute features** and after a morning **Garmin sync** that recomputed scores (in their own transaction afterwards; a failure there is logged and never undoes the scores or fails the sync); on demand with `docker compose run --rm app signals --athlete 1` or `POST /api/athletes/{id}/signals/recompute` (coach). PR 2 adds: after each check-in. The whole history is recomputed each time; it is a few hundred days of arithmetic.
- **Read:** `GET /api/athletes/{id}/signals?from=&to=` (AccessPolicy; default the last 28 days; at most 400 days).

### D9. Readiness (PR 2)
Module 1's recovery and band from the ACTIVE model, per day:
- the change against the mean recovery of the scored days among the previous **3** and **7** days (`signals.readiness.*`);
- how many scored days in a row the band has lasted, and how many in a row were not green (for the rule "yellow 2+ days in a row"). A day without a score ends a streak: unknown is not "still yellow".

Overnight HRV exists only from 2025-09-17, so days before the athlete's first HRV day carry no readiness (`beforeHrv`). Trends says so under the signals.

### D10. Sleep debt (PR 2) [configurable]
Over the last **7** nights including the day: sleep need (Module 1) minus minutes asleep (`day_features.sleep_minutes`, read only), walked oldest night first. A longer night pays back an earlier shortfall, and the debt never goes below 0, so a long night does not pre-pay a later short one (coach, `surplusPaysBack`). A night without a need or a recorded sleep is skipped, not counted as 0 hours. `nightsOfNeed` = debt ÷ the mean need, for the rule "sleep debt > 1 night's need".

### D11. Check-in flags, session demand, running tolerance (PR 2)
- **Check-in flags** (from `subjective_current`):
  - RPE of the day and the highest session RPE;
  - the highest soreness;
  - illness and injury that day, and the number of days with each in the last 7.
- **Niggle episodes per body area:** an episode starts with a niggle report and lasts until the athlete marks it gone, or until it hasn't been reported for **7** days (`signals.subjective.niggleStaleDays`, a heuristic: nobody reports "gone" every time). `persistDays` counts from the first report, for the rule "persists 3+ days".
- **Flagged days:** a day with a niggle (not marked gone) or an injury report is *flagged*. Illness alone does not flag.
- **Session demand:** the day's planned sessions that still count (not skipped or replaced), with the highest demand. Stored for days up to today; the engine (2-3) reads upcoming sessions from the plan itself. A plan edit does not trigger a recompute; the next recompute picks it up.
- **Running tolerance** (spec), in km per week, per day:
  1. **Peak:** the best average weekly km over any **4**-week window inside the last **365** days with no flagged day.
  2. **Decay:** walking back in 7-day blocks to the peak window, a block under **50 %** of the peak is an interrupted week (coach). Each interrupted week beyond the first takes **10 %** of the peak off, down to 0.
  3. **Cap:** the chronic running volume (EWMA of daily running km over the chronic span, × 7) × **1.0** (`chronicCapFactor`).
     - 1.0 is the spec's literal cap. The coach said "take the recommendations", but I had not recommended a value here, so this is the literal reading, flagged.
     - With 1.0 the tolerance mostly equals the recent average, and the 12-month peak matters mainly after a break. A value above 1 leaves room.
     - Before the chronic value exists, no cap is applied, and that is said.
  4. **Override:** the coach's newest override replaces the number from the day it was set. The computed number stays visible beside it.
  5. **Derivation:** stored with the number and shown step by step on the Athletes screen.
- **No flags in the history:** there are no niggle or injury flags before the check-in existed (the Garmin seed carries effort only), so older windows count as clean. The override is the safety valve (coach).
- **The override is kept as a history:** table `tolerance_override` (V24), append-only by the same trigger as ADR 0018. Each row has km per week (empty = cleared), a note, who set it and when.
  - `GET /api/athletes/{id}/tolerance` (AccessPolicy);
  - `POST /api/athletes/{id}/tolerance/override` (coach), which recomputes the signals at once.
- **Signals version** becomes `s2` (all sections). The PR 1 rows (`s1`) stay until someone drops them; they are derived data.
- **When (addition to D8):** also after each check-in (the controller calls it after the check-in is saved; a failure never loses the check-in) and after an override. The signal recompute runs in its own transaction.
- **Settings:** one new setting (`signals.subjective.niggleStaleDays`). The first read after the update saves it as a new settings version (D2).

### D12. Screens (PR 2)
- **Trends:**
  - a chart of the last-7-days running km against the tolerance;
  - an "Engine signals" card with the newest day's signals in plain words: readiness and trend, sleep debt, both ACWRs ("one signal among several, not a verdict"), monotony and strain, running against tolerance, and open niggles, illness or injury;
  - the HRV start note.
- **Athletes:** for the selected athlete, the tolerance, its derivation, how much of it this week used, the override form (km and why), "use the computed value", and the override history.
- Both screens work at 390 px, light and dark, with every string an i18n key. Screenshots: `docs/screenshots/2-2/`.

## Why the live holdout and the golden check are unaffected
- PR 1 adds **no migration**. It writes only `settings_version` and `day_signals` (both from V22), and reads `day_features`, `activity_with_cycle` and `activity_load` without changing them.
- PR 2 adds V24: a new table `tolerance_override` and a column comment on `whoop_reference.cycle_date` (the R-15 backlog item; a comment changes no row). It additionally reads `derived_day_metrics`, `subjective_current` and `planned_session`. `FEATURE_VERSION` stays `f3`; no score is computed differently.
- The signals run **after** the feature recompute, in the controller and the sync, not inside `FeatureRecomputeService.recompute`. The golden check (ADR 0017) replays exactly that method and hashes `day_features`, `derived_day_metrics` and the raw tables, none of which this stage writes. So it still prints **0 differences**.

## Tests
`EwmaTest` (the Trends cases), `LoadSignalsTest` (ACWR, running-only load, Foster with and without enough days and with equal days, running windows, grid and settings checks), `SettingsCatalogTest`, `SettingsIT` (defaults saved once, a new value becomes a new version, append-only, the demand of a new session from the store, coach-only API), `SignalsIT` (a synthetic year with a watch-off fortnight, a hard block and a taper; a settings change adds rows under the new version; a rerun replaces; access), `SignalsCliTest`, `SessionValidatorTest`. PR 2: `ReadinessTest`, `SleepDebtTest`, `SubjectiveTest`, `ToleranceTest` (peak, flagged windows, decay and its floor, cap, 12-month limit, override, no running), `SignalsIT` additions (all sections over the synthetic year; override set, refused for an athlete or out of range, cleared, append-only; a check-in through the API recomputes), frontend `signals.test.ts`, `ToleranceCard.test.tsx`, `TrendsPage.test.tsx` additions.
