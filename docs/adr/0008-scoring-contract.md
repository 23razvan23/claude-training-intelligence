# ADR 0008: Scoring contract: how a model version turns `day_features` into daily scores

- Status: accepted (before stage 1b-1; review item R-01)
- Code references are to commit `c735670`. If a cited line moves, the rule still holds. The R-03 parity fixture is the lasting check.

## In plain words
The daily scoring service (stage 1b-1, Java) must give exactly the numbers the calibration (Python) would give for the same day. Otherwise the live holdout would be testing a different model from the one that passed.

Most of the recipe is the coefficients stored with the model version (ADR 0007). But a chain of in-between values exists only as Python code:

today's strain → tomorrow's "yesterday's strain" → 7-day sleep shortfall → sleep need → sleep performance → recovery.

So do the rules for gaps (watch off, no sleep recorded), clipping and rounding. This ADR writes all of that down as numbered rules. Each rule has a worked example with made-up numbers. **It describes what Python does today; it does not change it.**

## Context
- The coefficients JSON (`pipeline.py:207-213`) holds:
  - `strain`: the form and its parameters, see `_strain_coefficients`, `pipeline.py:63-80`;
  - `sleep`: `base_min`, `per_strain_min`, `per_shortfall`, `shortfall_reference_min`, `shortfall_days`;
  - `recovery`: `intercept`, `terms[]` (each with `feature`, `sign`, `weight`, `center`, `scale`), and `clip`;
  - `bands`: `green_min`, `red_max`.
- Scoring always uses the coefficients of the athlete's **ACTIVE** model version, never the current `targets.json`. Today that version is m13-20261113 (feature version f3, form "Edwards workout load + background").
- **Inputs (assumption A2):** the service reads the stored `day_features` row of the model's `feature_version`. Values are exactly as stored, already rounded by `DayFeatureService.dec` (`DayFeatureService.java:285`). This is what Python reads (`data.py:27-39`). The service must not recompute features in memory.

## Synthetic example used throughout
These are made-up coefficients and data. R-03 should put this exact grid in its fixture.

| Coefficient | Value |
|---|---|
| strain | form `load`, `load = day_load_edwards`, `uses_background = true`, a = 5.0, b = 0.01, c = 2.0, `background_weight` k = 0.2, `min_monitored_minutes` = 600 |
| sleep | `base_min` 400, `per_strain_min` 3.0, `per_shortfall` 0.05, `shortfall_reference_min` 450, `shortfall_days` 7 |
| recovery | intercept 55, clip [0, 100]. Terms (feature, sign, weight, center, scale): `ln_rmssd_z` +1, 8, 0.1, 1.0 · `rhr_dev` −1, 5, 0.2, 2.5 · `resp_dev` −1, 2, 0.0, 0.5 · `sleep_perf` +1, 6, 90, 10 · `prev_strain` −1, 3, −9.33, 3.0 |
| bands | green ≥ 67, red ≤ 33 |

| Date (2026-03-) | Edwards load | Background load | Monitored min | Sleep min | ln_rmssd_z | rhr_dev | resp_dev | has_garmin_core | Note |
|---|---|---|---|---|---|---|---|---|---|
| 01 | 120 | 300 | 900 | 480 | 0.5 | −1.0 | −0.2 | true | first day of data |
| 02 | 60 | 250 | 840 | 400 | 0.2 | 0.5 | 0.1 | true | |
| 03 | 300 | 280 | 880 | 380 | −0.4 | 2.0 | 0.4 | true | |
| 04 | 0 | null | null | 460 | 0.1 | 0.0 | 0.0 | true | watch off all day (no all-day HR) |
| 05 | – | – | – | – | – | – | – | – | **no `day_features` row** |
| 06 | 90 | 120 | 300 | null | 0.3 | −0.5 | 0.0 | false | partial wear, no sleep recorded |
| 07 | 40 | 260 | 860 | 450 | 0.0 | 0.0 | 0.0 | true | |
| 08 | null | 270 | 870 | 470 | 0.6 | −1.5 | −0.3 | true | workout recorded without HR |
| 09 | 80 | 240 | 850 | 420 | 0.4 | −0.5 | −0.1 | true | |
| 10 | 5000 | 2000 | 900 | 500 | 0.2 | 0.0 | 0.0 | true | extreme load (tests clipping) |

Results, computed with the real Python functions (unrounded; "–" = blank):

| Date | Strain | Low cov. | prev_strain | Shortfall | Sleep need | Sleep perf | Recovery | Band |
|---|---|---|---|---|---|---|---|---|
| 01 | 7.1481 | no | – | 0 | 400.0000 | 100.0000 | – (`NO_STRAIN_YESTERDAY`) | – |
| 02 | 5.7097 | no | 7.1481 | 0 | 421.4443 | 94.9117 | 59.1289 | YELLOW |
| 03 | 9.5866 | no | 5.7097 | 50 | 419.6291 | 90.5562 | 48.9540 | YELLOW |
| 04 | – | yes | 9.5866 | 120 | 434.7598 | 100.0000 | 60.3434 | YELLOW |
| 05 | – | yes | – | 120 | 406.0000 | – | – (`NO_DAY_DATA`) | – |
| 06 | 5.8040 | **yes** | – | 120 | 406.0000 | – | – (`NO_STRAIN_YESTERDAY`, `NO_SLEEP`) | – |
| 07 | 5.2616 | no | 5.8040 | 120 | 423.4121 | 100.0000 | 63.3260, **lower confidence** | YELLOW |
| 08 | – | no | 5.2616 | 120 | 421.7849 | 100.0000 | 72.8684 | GREEN |
| 09 | 6.1209 | no | – | 120 | 406.0000 | 100.0000 | – (`NO_STRAIN_YESTERDAY`) | – |
| 10 | 21.0000 | no | 6.1209 | 100 | 423.3626 | 100.0000 | 64.6091 | YELLOW |

## Rules

### S1. The day grid
- **Rule:** days are consecutive calendar dates (`cycle_date`, the wake day, ADR 0002), from the athlete's first to last `day_features` date for the model's feature version. A date inside that range with no row is treated as a row whose every input is missing. "Yesterday" always means the previous calendar date, never "the previous row".
- **Mirrors:** `pipeline.py:31-37` (`reindex` onto `pd.date_range(min, max, freq="D")`).
- **Consequences:**
  - To score day D, the service needs the rows for D and D−1, plus the sleep minutes of D−7 … D−1 (S4). It does not need the whole history.
  - **Days after the last `day_features` date are not scored at all.** This is the "stale data" case, see C2.
- **Example:** 03-05 has no row, but it is still a grid day. 03-06's "yesterday" is 03-05 (blank), not 03-04.

### S2. Strain
- **Rule:** compute it for the stored form, then clip to [0, 21]. Strain is blank when the form's inputs are missing. Missing values are not treated as 0.

  | Form | Formula | Blank when (reason) |
  |---|---|---|
  | `load`, `uses_background = true` | c + a·ln(1 + b·(load + k·background_load)) | `load` column is null (`MISSING_WORKOUT_HR`), or `background_load` is null (`NO_ALL_DAY_HR`). This applies **even if k = 0**. |
  | `load`, `uses_background = false` | c + a·ln(1 + b·load) | `load` column is null (`MISSING_WORKOUT_HR`). |
  | `zones` | c + a·ln(1 + Σ_z zone_weights[z]·zone_min_z), z = 0…5 | any of `zone_min_0…5` is null (`NO_ZONE_MINUTES`). |

  A grid day with no row is blank with `NO_DAY_DATA`. Here ln is the natural log.
- **Low coverage:** `low_coverage` = (monitored_minutes, with null counted as 0) < `min_monitored_minutes` (600 **[configurable]**: below 10 h of all-day HR, background load is partial; ADR 0007). Low-coverage days **are scored**, and the score is flagged. Use the value stored in the coefficients, not `targets.json`.
- **Mirrors:** `strain.py:191-205` (blank rules, with `fillna(0)` only under a "usable" mask), `strain.py:51-54` and `:76-78` (formulas, clip 0–21), `pipeline.py:109`, `pipeline.py:115-118` (low coverage).
- **How Java fills these columns** (`DayFeatureService.java`):
  - **Load is null** when that day had a Garmin activity whose HR could not be used (`:241-242`). A Garmin day without activities has load 0.
  - **`background_load` is null** when the day has no all-day HR sample at all (`:257`). Wearing the watch for part of the day gives a partial value, not null.
- **Examples:**
  - 03-01: 2 + 5·ln(1 + 0.01·(120 + 0.2·300)) = 2 + 5·ln 2.8 = **7.1481**.
  - 03-10: 2 + 5·ln(55) = 22.04 → clipped to **21**.
  - 03-04: load 0, background null → blank (`NO_ALL_DAY_HR`). 03-08: load null → blank (`MISSING_WORKOUT_HR`).
  - 03-06: 300 monitored minutes → scored 5.8040 and flagged low coverage.
  - Zones form (separate example: c = 1, a = 4, weights 0.001, 0.02, 0.05, 0.1, 0.2, 0.3; minutes 600, 120, 60, 30, 10, 0): 1 + 4·ln(1 + 11) = **10.9396**.

### S3. Yesterday's strain (`prev_strain`)
- **Rule:** `prev_strain(D)` = the **unrounded** strain of D−1 from S2 (blank if that is blank, or if D−1 is before the first grid day). Never use the rounded value stored in `derived_day_metrics.strain`.
- **Mirrors:** `pipeline.py:119` (`strain_pred.shift(1)` on the S1 grid).
- **Example:** prev_strain(03-02) = 7.1481, not 7.1. prev_strain(03-01) is blank (first day). prev_strain(03-05), (03-06) and (03-09) are blank, because 03-04, 03-05 and 03-08 have no strain.

### S4. 7-day sleep shortfall
- **Rule:** shortfall(D) = Σ over d = D−N … D−1 of max(0, R − sleep_minutes(d)).
  - N = `shortfall_days` (7 **[configurable]**: a one-week memory for sleep debt, SPEC.md).
  - R = `shortfall_reference_min` (the training-window median of Whoop's sleep need).
  - A night with no `sleep_minutes`, and any date before the first grid day, adds **0**.
  - Day D's own sleep is **not** included.
  - Shortfall is never blank. On the first grid day it is 0.
- **Mirrors:** `sleep.py:16-19` (`clip(lower=0).fillna(0)`, `shift(1)`, `rolling(days, min_periods=1).sum()`, final `fillna(0)`).
- **Example:** shortfall(03-04) = (450−480 → 0) + (450−400 = 50) + (450−380 = 70) = **120**. shortfall(03-10) covers 03-03 … 03-09: 70 + 0 + 0 (03-05 missing) + 0 (03-06 missing) + 0 + 0 + 30 = **100**.

### S5. Sleep need and sleep performance
- **Sleep need:** need(D) = base_min + per_strain_min·P + per_shortfall·shortfall(D).
  - P = prev_strain(D) from S3, or **0 when it is blank** (see L1).
  - Need is therefore never blank.
- **Sleep performance:** perf(D) = clip(100·sleep_minutes(D) / need(D), 0, 100).
  - It is blank when `sleep_minutes` is null (reason `NO_SLEEP`).
  - It is 0 when sleep_minutes is 0.
- **Mirrors:** `pipeline.py:126` (`prev_strain.fillna(0)`), `sleep.py:32-33` (need), `pipeline.py:127` and `sleep.py:36-37` (performance).
- **Examples:**
  - need(03-02) = 400 + 3·7.1481 + 0.05·0 = **421.4443**. perf(03-02) = 100·400/421.4443 = **94.9117**.
  - need(03-03) = 400 + 3·5.7097 + 0.05·50 = **419.6291**. perf = 100·380/419.6291 = **90.5562**.
  - need(03-09) = 400 + 3·**0** + 0.05·120 = **406** (yesterday's strain blank → counted as 0).
  - perf(03-06) is blank: no sleep recorded.

### S6. Recovery
- **Formula:** recovery_raw(D) = intercept + Σ_terms contribution_t.
  - contribution_t = weight_t · (sign_t · x_t − center_t) / scale_t, where x_t is the value of `feature_t` on day D.
  - The model's inputs are exactly its `terms[]`: the five core features (`ln_rmssd_z`, `rhr_dev`, `resp_dev`, `sleep_perf`, `prev_strain`), plus `sleep_spo2` / `body_battery_high` if the calibration selected them.
  - `sleep_perf` comes from S5 and `prev_strain` from S3. The rest are `day_features` columns.
- **Signed-center convention:** `center` and `scale` were computed on the *already sign-flipped* value (sign·x). For a "lower is better" feature (sign = −1), the stored center is the mean of −x: it is usually negative for `prev_strain`. **Do not flip the center's sign again.**
- **Clip:** recovery = clip(recovery_raw, `clip[0]`, `clip[1]`) = [0, 100]. Each contribution is stored as computed (unclipped) for the explanation.
- **Blank when** any term's input is blank, or `has_garmin_core` is false (A1). In that case there is no score and no band. The reasons are recorded as a list, one per missing input:

  | Missing input | Reason |
  |---|---|
  | no `day_features` row for D | `NO_DAY_DATA` |
  | `ln_rmssd_z` (no HRV that night, or no HRV baseline yet) | `NO_HRV` |
  | `rhr_dev` | `NO_RESTING_HR` |
  | `resp_dev` | `NO_RESPIRATION` |
  | `sleep_perf` (no sleep recorded) | `NO_SLEEP` |
  | `prev_strain` (yesterday's strain blank; carry yesterday's S2 reason as detail) | `NO_STRAIN_YESTERDAY` |
  | `has_garmin_core` false with all of the above present (e.g. a 0-minute sleep) | `NO_GARMIN_CORE` |
  | an optional term (`sleep_spo2`, `body_battery_high`) | `NO_SPO2`, `NO_BODY_BATTERY` |

- **Mirrors:**
  - `recovery.py:47-50` (contribution formula), `recovery.py:54-56` (intercept + sum, clip), `recovery.py:42` (clip [0, 100]);
  - `recovery.py:25-28` (center/scale on sign·x);
  - `pipeline.py:131` (`has_garmin_core`), `pipeline.py:138` and `:147-148` (rows with any missing input are dropped).
- **Warning for the implementer:** `recovery.predict` *on its own* does **not** return a blank for a missing input. pandas' `sum(axis=1)` skips NaN, so a missing term silently counts as 0. In our synthetic grid, `predict` gives 55.0 (the bare intercept) for 03-05, which has no data at all. Calibration only ever called it on complete rows (the `dropna` lines above). The blank rule above is the contract; a line-by-line port of `predict` would be wrong.
- **Example (03-02):**
  - `ln_rmssd_z`: 8·(0.2 − 0.1)/1 = 0.8
  - `rhr_dev`: 5·(−0.5 − 0.2)/2.5 = −1.4
  - `resp_dev`: 2·(−0.1 − 0)/0.5 = −0.4
  - `sleep_perf`: 6·(94.9117 − 90)/10 = 2.9470
  - `prev_strain`: 3·(−7.1481 − (−9.33))/3 = **+2.1819**. A yesterday's strain below the training average (≈ 9.33) adds points.
  - Total: 55 + 0.8 − 1.4 − 0.4 + 2.9470 + 2.1819 = **59.1289**.

### S7. Rounding and bands
- **Rule:** all arithmetic uses unrounded doubles, through S1–S6. Rounding happens once, at the end, **half-up** (Java `RoundingMode.HALF_UP`, as `DayFeatureService.dec` already does), to the storage types in `derived_day_metrics` (`V3__derived_and_planning.sql:9-13`):

  | Value | Stored as |
  |---|---|
  | recovery, sleep performance, sleep need | integer |
  | strain | 1 decimal |

- **The band comes from the unrounded recovery:** GREEN if ≥ `green_min`, RED if ≤ `red_max`, otherwise YELLOW. The thresholds are 67 and 33 **[configurable]**: the conventional Whoop-style split, stored per model version.
- **Mirrors:** `metrics.py:32-37` (`band`), applied to unrounded predictions in `pipeline.py:156`. Python itself never rounds; the rounding is new and only affects storage and display.
- **Examples:**
  - Strain 7.1481 → 7.1; 9.5866 → 9.6. Sleep need 421.4443 → 421. Perf 94.9117 → 95.
  - **Recovery 66.6 → stored 67, band YELLOW** (66.6 < 67). Recovery 33.5 → stored 34, band YELLOW. Recovery 67.0 → 67, GREEN.
  - The coach accepted on 2026-11-15 that a stored 67 can show yellow, because the bands were validated on unrounded values (C3).

### S8. Low-confidence flag on recovery
- **Rule:** when recovery(D) is computed and day D−1 was `low_coverage` (S2), recovery(D) is marked **lower confidence**, with reason `LOW_COVERAGE_YESTERDAY`. Yesterday's strain, and so today's recovery, used a partial all-day load.
  - This flag never changes a number.
  - Day D's own strain carries its own `low_coverage` flag.
- **Mirrors:** nothing in the scoring path. Python flags low coverage only on strain (`pipeline.py:115-118`, `:173`). This is a **new display rule**, safe because it changes no value.
- **Example:** 03-06 has 300 monitored minutes, so its strain is low coverage. Recovery(03-07) = 63.3260, shown as 63, YELLOW, lower confidence.

## Coach decisions recorded
- **C1 (asked 2026-11-13, confirmed 2026-11-15): when yesterday's strain is unknown, this morning's recovery is "not available", with the reason (option a).** Yesterday's strain is unknown in three cases:
  - no all-day HR at all yesterday;
  - a workout recorded without HR yesterday;
  - no Garmin data for yesterday.

  If the watch was worn for only part of yesterday, recovery is shown, marked lower confidence (S8). Why: this is what the calibrated model does. Showing a number anyway (option b) would need a new calibration.
- **C2 (2026-11-13 / 2026-11-15): stale data is a separate case.** Dates after the athlete's last `day_features` date get no score at all, and the app says "last data from <date>". It does not show "not available" reasons. The coach's current exports were taken around 8–9 Nov 2026, so nothing after that has data until the next import (or daily sync, stage 1b-4).
- **C3 (2026-11-15):** keep bands on unrounded recovery, even though a stored 67 can be yellow (S7).

## Assumptions (flagged for review)
- **A1:** recovery also requires `has_garmin_core` (HRV, resting HR and > 0 minutes of sleep), because calibration only used such days (`pipeline.py:131`). In practice this only adds the case of a recorded 0-minute sleep. Recovery-relevant columns are otherwise already covered by the blank rules.
- **A2:** the service reads stored `day_features` values (see Context).

## Known limitations (revisit at the next recalibration)
- **L1: a missing yesterday's strain is treated three different ways.**
  - Sleep need counts it as **0** (`pipeline.py:126`).
  - The sleep-need fit **drops** those days (`sleep.py:23`).
  - Recovery **requires** it (`pipeline.py:138`).

  The contract keeps all three, so the active model stays valid. Effect: on a morning after a watch-off day, the sleep need shown is too low by per_strain_min × (yesterday's real strain) minutes, and sleep performance is too high. Recovery for that morning is blank anyway (C1). Fixing it means changing Python and recalibrating.
- **L2:** `recovery.predict` alone does not blank missing inputs (S6 warning). A future calibration code change could make it do so; until then, S6 is authoritative.
- **L3:** if sleep need were ever ≤ 0, sleep performance would divide by zero. Python would give inf or NaN, clipped to 100 or left blank. This can't happen with the stored bounds (base ≥ 0, other terms ≥ 0) unless all three coefficients are 0. The service should then report the performance as blank.
- **L4:** a stored 67 can be YELLOW (S7, C3).
- **L5:** `uses_background = true` with k = 0 still blanks strain on days without all-day HR (S2), although the value would not change. This mirrors Python's mask.

## Consequences
- Stage 1b-1 implements S1–S8 and nothing else. Its parity test (R-03) uses the synthetic grid above and must reproduce the results table to within floating-point tolerance, before rounding (S7).
- Changing any rule here is a model change: change the Python, recalibrate, and update this ADR in the same PR.
