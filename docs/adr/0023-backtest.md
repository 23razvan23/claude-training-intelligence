# ADR 0023: Backtest of the adaptive engine (stage 2-4)

- Status: accepted (stage 2-4 PR 1, 2026-11-20). The coach answered the plan's questions with "go with your suggestions", taken as accepting every recommendation (Q1–Q7). **Still open:** the dates of races and of the hard weeks the coach remembers (Q1, Q3), asked again before PR 2.
- Related: SPEC.md ("the Phase 2 backtest matters most"); ADR 0018 (D6 "latest wins", D7 `day_signals`); ADR 0020 (check-ins and the Garmin RPE seed); ADR 0021 (signals, settings store, tolerance amendment); ADR 0022 (rules engine r1); ADR 0010 (raw revisions); ADR 0008 and ADR 0017 (scoring and golden check, **unchanged**).
- Two PRs. **PR 1** (this one): the replay, the no-look-ahead test, storage (V26) and the `backtest` command. **PR 2**: the HTML report and the screen where the coach marks a sample of suggestions.

## In plain words
The backtest asks: "if the app had been running for the last 12 months, what would it have suggested each morning?" For every past morning it computes the signals from only the data that existed by that morning, then runs the real rules engine on a stand-in plan made from what you actually did. The results are kept apart from your real plan and suggestions, under a run number, so running it changes nothing you use.

## Decisions

### D1. The live code, not a copy
- The signal arithmetic moved out of `SignalService` into `SignalCalculator` (pure). The live recompute reads the database and calls it, and the backtest calls it with the rows of a past morning. Nothing in it changed; the signals version stays `s3`. The existing `SignalsIT` and `SuggestionIT` pass unchanged, which shows the live output is the same.
- How the engine's input is built from stored signals moved into `SuggestionService.input(…)`, which the live run and the backtest both call. The rules are `RulesEngine.evaluate`, rules version `r1`, unchanged.
- The signals of each replayed morning are turned into JSON exactly as `day_signals` stores them (`SignalRepository.toJson`), and read back by the engine's own reader (`SuggestionService.state`).

### D2. What "that morning" means (no look-ahead)
For the morning of day D (class `AsOf`):
- **Body data** (load, running, sleep, scores): everything of the days before D, plus D's own night and recovery score. D's own load and running are left out, because they happen after waking. The live engine also reads only yesterday's load (ADR 0021 D5). All data before November 2026 came in one bulk export, so the import time says nothing about when a row existed; its own date does.
- **Rows with a typing time** (manual check-ins, tolerance overrides): only those saved before **09:00** local time on D (`--cutoff`, [configurable]: after the 07:00 sync and a usual morning check-in; coach, Q6). "Latest wins" (ADR 0018 D6) is applied among those rows only, so a correction typed later cannot reach back. Today the database holds 3 manual check-ins and 0 overrides, all from November 2026.
- **Garmin RPE seed rows** (143): you entered the RPE in Garmin right after the activity. So a row counts from the morning after its activity's day, not from the day it was imported (2026-11-18).
- **Settings:** one settings version for the whole run, by default the current one (`--settings-version N` for another), recorded on the run. The backtest tests today's method; no settings existed in the past.
- **The stand-in plan** for the days ahead is known in advance by design (D4). That is the only thing from after the morning that the engine sees.

**Test:** `NoLookAheadIT` replays one morning, then adds rows "from the future" and replays again:
- a check-in typed after the morning (an illness that day, a back-dated niggle);
- a tolerance override saved later;
- Garmin RPE for the day's own and a later activity;
- a big block and worse scores, HRV and sleep on every later day;
- a second run that afternoon, and the day's evening load.

The signals and suggestions must stay identical. The control test adds the same rows dated *before* the morning and checks that they do change the result (illness rule, the tolerance becomes 5 km/week). Breaking the filter on purpose makes three of the tests fail. One more test checks that a correction typed later does not reach back, and one that "latest wins" with every row kept gives exactly what the view `subjective_current` gives.

### D3. Look-ahead the backtest cannot remove (stated in the report)
1. **Scores come from today's model (m15).** The day's recovery is the one stored for the ACTIVE model. Each day's inputs only look backwards (the baselines use the previous 30 days, excluding the day). But the model's weights were fitted on Whoop days **2026-01-19 → 2026-09-09**, inside the window. So readiness there is "in-sample" and somewhat flattering, and the model did not exist then at all. This is hindsight about the model, not about your training. The stored scores are used because recomputing them would touch the frozen scoring (ADR 0008).
2. **Garmin's resting heart rate** for a day is the whole-day value in the export; on the real morning it may have been different. Removing this would mean changing features, which is frozen while the live holdout runs.
3. **Late corrections (ADR 0010):** a Garmin day sent again uses its newest version. Your database has **14** corrected daily summaries and no corrected sleep, all from the live-sync weeks of November 2026. Each morning whose own or previous day was corrected after its cut-off gets a note saying so, and the run counts them.
4. **The plan is hindsight** (D4): a skipped week looks like a lighter plan, and the rule that restores missed volume can never fire.

### D4. Your activities as "the plan" (Q1, coach: "go with your suggestions")
Every **Garmin** activity that counts becomes one session on its local date (class `ActivityPlan`). Whoop copies of workouts are not counted (about half of the activity rows in your 12 months are Whoop's "Running", "Walking" and so on), as in the signals.

**Runs** are classified by heart-rate-reserve zones as the features compute them (zone 4 = 80–90 % HRR, zone 5 = 90 %+). Every threshold is [configurable] and a heuristic:

| Type | When | Why |
|---|---|---|
| race | the date is in `--races` | Garmin's export does not say which runs were races |
| long | ≥ 90 min or ≥ 18 km | 11 runs in your last 12 months qualify (avg 18.5 km) |
| long with fast finish | long, and ≥ 10 min above 80 % | the end of a long run at tempo |
| VO2max | ≥ 8 min above 90 % | typical interval work spends 8–20 min there; 18 runs |
| threshold | ≥ 15 min above 80 % | the shortest common tempo block; 26 runs |
| easy | everything else, and runs without heart rate | |

- **Cross-training:** cycling, strength, swimming, hiking, and Garmin's HIIT and squash.
- **Left out:** walks, breathwork, yoga and unnamed activities.
- **Key / supporting / easy:** the current settings' default demand per type (`planning.defaultDemand.*`).
- Distance and time are what was done. Sessions before the morning are DONE, the rest PLANNED. No session is taper, so only race week (from `--races`) protects sessions.

### D5. Each morning stands alone (Q2)
- No suggestion is taken as accepted. The plan stays what was actually done, nothing counts as already decided that day, and no restore step counts as recently accepted.
- So the backtest shows what the engine would have *said*, not what would have happened if you had followed it.

### D6. Storage: separate tables under a run id (V26)
- `backtest_run`: window, parameters (cut-off, races, hard weeks, classification), rules / signals / settings / model version, a summary (counts per rule and action, first HRV day, first morning with readiness, mornings with late corrections).
- `backtest_day`: per morning, today's and yesterday's signals as the rules read them, health messages and notes.
- `backtest_suggestion`: per suggestion, the morning, rule, action, the activity (and swap partner), before/after (the "after" in the shape the plan API takes, as live), 1–3 reasons, confidence and the inputs snapshot.
- `backtest_mark`: your verdicts on the sample (Sensible / Not sensible / Unsure, a note), **append-only** (a change of mind is a new row; the newest counts). They become training data for stage 4-3. The screen comes in PR 2.

Results must be stored rather than kept in memory, because the marks must point at them. Nothing writes to `adaptation_suggestion`, `planned_session` or `day_signals`: `BacktestIT` checks that their counts don't change. Deleting the athlete removes the backtest rows too. A run is one transaction: a failed run leaves nothing behind. Old runs stay until deleted, so a later run with other settings can be compared.

### D7. HRV and the window (Q7)
- The default window is the last 12 months up to yesterday (`--from`/`--to` for another). Overnight HRV starts 2025-09-17, before the window opens on 2025-11-20.
- So recovery-based rules (red, yellow, and sleep debt, which needs the sleep need from the scores) can run from the first morning that has a score. The run records that morning, and the report will show it.
- Before 2026-11-18 there are no manual check-ins, so the **illness and niggle rules cannot fire in the history**: only recovery, sleep and volume can flag a week.

### D8. Red-flag weeks and the sample (PR 2, recorded now)
- **Red-flag week** [configurable]: a week with any red-day or yellow-streak suggestion, any high-confidence volume suggestion, or 3 or more suggestions.
- **Sample to mark:** about 40 suggestions, spread across the rules, plus every suggestion in your hard weeks.

## Running it
`docker compose run --rm app backtest --athlete 1 [--races YYYY-MM-DD,…] [--hard-weeks YYYY-MM-DD,…]`

It prints the run number, the mornings, the suggestions per rule, the first morning with readiness and the late corrections. Expect up to a minute: each of 365 mornings recomputes about two years of signals.

## Why the live holdout, live suggestions and the golden check are unaffected
- **V26** adds four new tables only. Nothing in `metrics/` or `ingestion/` changed; `day_features`, `FEATURE_VERSION` (`f3`) and scoring are untouched. So the golden check (ADR 0017), which replays Recompute features and hashes `day_features`, the ACTIVE model's scores and the raw tables, still prints **0 differences**.
- The live signals (`s3`) and rules (`r1`) behave exactly as before: their code was moved, not changed (D1). No new setting was added, so no new settings version appears and no pending suggestion expires.

## Tests
- `NoLookAheadIT` (D2);
- `AsOfTest`: the 09:00 cut-off in the athlete's timezone, typed rows vs seed rows, late-correction notes;
- `ActivityPlanTest`: every classification edge, races, cross-training, what is left out, DONE/PLANNED and the settings' demand;
- `BacktestIT`: a full run stored under its id with the races and hard weeks, live tables untouched, marks append-only, cascade on athlete delete, the command;
- `BacktestCliTest`: options and defaults.
