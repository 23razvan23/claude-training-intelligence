# ADR 0009: Live holdout protocol: the rules for the comparison with Whoop, fixed before its data is seen

- Status: accepted (2026-11-15, before stage 1b-3; review item R-07)
- Applies to: every ACTIVE model version from m15-20261115 on.

## In plain words
Whoop stays active until **19 February 2027**. Until then, every new day lets us compare our scores with Whoop's on days the model has never seen. That comparison is the evidence for cancelling Whoop.

The 1a test window is no longer fully clean evidence. Three choices were made after its results were seen: the parsimony rule, the revised strain targets (MAE ≤ 2.0, Spearman ≥ 0.80) and the "≥ 10 h of all-day HR" filter (ADR 0007 items 3 and 6). The live comparison stays clean only if its rules are written down **before anyone looks at its numbers**. This ADR writes those rules down. Nobody may change them for a model once its comparison has started. A change of rules applies only to a later activation (H8).

The 1a evidence is also thinner than "0 green-vs-red flips" suggests. With 55 test days, the 95% Wilson interval for 0 of 55 is 0–6.5%, so 1a cannot show that the real rate is below the 3% target.

## Rules

### H1. Which model
- The comparison always belongs to **one model version**: the athlete's ACTIVE version. Only scores written with that version are compared (`derived_day_metrics.model_version`, stage 1b-1).
- Scores are computed from the data as imported, whenever that is. The holdout tests the model, not how early in the morning the score was ready.
- A retired version's comparison is kept as it was when it was retired. It is never merged with the next version's.

### H2. Which days (the window)
- **From:** the day after the model's test window (`test_to` + 1). Every such day is unseen by the fit. The date is frozen as `holdout_targets.window_from` (H3).
  - Coach decision D2, 2026-11-15: this option was chosen over "the day after activation". It gives the model every unseen day, and a future model doesn't lose the days between its test window and its activation.
  - Known trade-off: between a model's test window and its activation, the previous model's comparison may already be on screen, and could sway which candidate gets activated. Mitigation: activate soon after calibrating, and H8 (a new activation restarts the count).
- **To:** the last day with a finished Whoop cycle in the database. Whoop is cancelled on 2027-02-19, so the last export must be taken on or after that day.
- **For m15-20261115:** test window 2026-09-10 → 2026-11-08, so the window runs from **2026-11-09** to at most **2027-02-19**: 103 calendar days.
- **Days already partly seen:** 2026-11-09. Whoop's recovery for that day (77, from a then-unfinished cycle) was one day inside m14's test result (88% band agreement, about 60 days). The comparison on that day was never looked at on its own. It is kept (coach decision D2). No other day after 2026-11-08 has been compared with Whoop.

### H3. Pass marks, frozen at activation
- Activation copies the pass marks into `model_version.holdout_targets` (JSONB, V15). After that, **nothing reads `calibration/targets.json` for the holdout.** Editing that file affects only future calibrations.
  - The marks come from the version's own `metrics.targets`: the marks it was calibrated and passed against. The Java app can't read `targets.json`, which lives in the Python package.
  - Activation is refused (409) when a version has no stored marks or no test window.
  - Activating the version that is already ACTIVE changes nothing, so the frozen marks cannot move.
- **m15-20261115** was activated before the column existed. V15 fills its column from its own stored marks, with `source: "V15"`. Its status and activation time are not touched (coach decision D3).
- Content of `holdout_targets`:

  | Key | m15 value | Meaning | Rationale |
  |---|---|---|---|
  | `recovery_band_agreement_min` | 0.70 | share of days in the same band as Whoop | [configurable] SPEC.md starting value. The coach kept it after 1a (D1). |
  | `recovery_green_red_max` | 0.03 | share of days where one side says green and the other red | [configurable] SPEC.md. A green-vs-red flip is the error that changes a training decision. |
  | `strain_mae_max` | 2.0 | mean absolute strain difference, full-coverage days | [configurable] revised by the coach 2026-11-13 (ADR 0007 item 6). |
  | `strain_spearman_min` | 0.80 | rank agreement of strain, full-coverage days | [configurable] same. |
  | `strain_min_monitored_minutes` | 600 | below this much all-day HR, strain is low coverage | [configurable] from the model's coefficients. With the watch off for most of a day, the strain score is partial (ADR 0008 S2). |
  | `min_recovery_days` | 90 | compared recovery days needed before a verdict (H6) | [configurable] coach decision D1: the most that 103 days can reliably give once some days drop out. 0 flips in 90 days still allows up to 4.1% (Wilson 95%). |
  | `confidence`, `interval` | 0.95, `wilson` | the interval shown next to every rate (H5) | [configurable] conventional 95%. Wilson behaves well near 0% and with ~100 days, where the simple ± formula fails. |
  | `window_from` | 2026-11-09 | first day of the window (H2) | `test_to` + 1 |
  | `protocol`, `source` | `ADR 0009`, `V15` / `activation` | which rules and how they were frozen | |

  The values for future activations are in `application.yml` (`app.metrics.holdout-min-recovery-days`, `holdout-confidence`).
- **Sleep performance and sleep need** are shown with n and MAE, but have no pass mark (none was set in 1a).

### H4. Days left out, and why
No day is ever left out by hand. Every day in the window is counted in exactly one of these groups, and the view shows the count of each.
- **Unfinished Whoop cycles:** never imported (R-06, ADR 0003), so never compared. When a later export holds the finished cycle, that day joins the comparison.
- **No Whoop score** that day: counted as "no Whoop".
- **No score from us:** counted under its ADR 0008 reason (`NO_DAY_DATA`, `NO_HRV`, `NO_RESTING_HR`, `NO_RESPIRATION`, `NO_SLEEP`, `NO_STRAIN_YESTERDAY` with its detail, `NO_GARMIN_CORE`, `NO_SPO2`, `NO_BODY_BATTERY`; strain: `MISSING_WORKOUT_HR`, `NO_ALL_DAY_HR`, `NO_ZONE_MINUTES`). Nothing is filled in.
- **Stale days** (after the last `day_features` date, ADR 0008 C2) are not compared until data arrives.
- **Recovery** is compared on every day where both sides have a recovery. Recovery marked lower confidence (ADR 0008 S8, `LOW_COVERAGE_YESTERDAY`) **is included**, as in 1a, and its count is shown. *(Assumption A2.)*
- **Strain:** the two strain marks are judged on days with ≥ `strain_min_monitored_minutes` of all-day HR. Low-coverage days are compared and shown separately, without a mark, as in 1a.

### H5. How rates are computed and shown
- The definitions are those of the calibration (`calibration/calibration/metrics.py`):
  - **Bands:** both sides' bands come from the unrounded recovery with the model's `bands` (67 / 33, ADR 0008 S7).
  - **Band agreement:** the share of days on the diagonal of the 3×3 matrix (rows = Whoop).
  - **Green-vs-red rate:** (Whoop green & ours red + Whoop red & ours green) ÷ n.
  - **MAE and Spearman:** ours − Whoop.
- **Every rate is shown with its n and its 95% Wilson interval.** For k of n with z = 1.96:
  - centre = (p + z²/2n) / (1 + z²/n), half-width = z/(1 + z²/n) · √(p(1−p)/n + z²/4n²), where p = k/n.
  - Examples: 0/55 → 0–6.5%; 0/90 → 0–4.1%; 3/90 = 3.3% → 1.1–9.3%; 79/90 = 87.8% → 79.4–93.0%.
- MAE and Spearman are shown with their n. They get no interval: a bootstrap would add moving parts for little decision value.
- Cumulative figures (whole window so far) are the evidence. Rolling figures (e.g. the last 4 weeks) and the weekly summary are for spotting drift, not for the verdict.

### H6. Minimum evidence and the verdict
- **"Not enough days yet"** while fewer than `min_recovery_days` (90) recovery days have been compared. No mark is called met or missed, but the numbers and intervals are shown.
- From 90 compared recovery days on, each mark is **met** when its measured value meets it, and **not met** otherwise. The interval is shown next to it (coach decision D1, option b; option c would have required the whole interval to meet the mark). The strain marks are judged at the same moment, on however many full-coverage strain days there are, with n shown. *(Assumption A1.)*
- **Overall: "pass"** only when all four marks are met. The recovery marks matter most: they drive training decisions (ADR 0007 item 6).
- **Final vs interim.** The decision report uses every day up to the last Whoop day. A verdict shown earlier is labelled **interim** and can still change. Stopping the first time a running count looks good would favour a pass. *(Assumption A3.)*
- If the window ends with fewer than 90 compared recovery days, the report says **"not enough days by the rule (n of 90)"** and still shows every number with its interval. The decision is then the coach's, taken knowing that, and recorded as such.

### H7. A simple baseline beside ours
- **Naive guess:** today's Whoop band (and recovery) = **yesterday's Whoop band** (and recovery).
  - It is computed on the days where our recovery, Whoop's recovery for that day and Whoop's recovery for the day before all exist. Ours is shown again on exactly those days, so the two are compared like for like.
  - Shown: band agreement, green-vs-red rate (each with n and interval) and recovery MAE.
  - It has no pass mark. It answers "is 88% better than just copying yesterday?".
- A day where Whoop has no recovery for the day before is left out of the naive comparison only. *(Assumption A4.)*

### H8. What restarts the holdout
- **Any new activation.** The new version gets its own `window_from` (its `test_to` + 1) and freezes its own marks, and the count starts again from 0. The old version's comparison is kept as history (H1).
- Re-pressing Activate on the ACTIVE version does nothing (ADR 0007 item 8).
- Things that force a new activation, and so a restart:
  - a new `feature_version`, i.e. any change to `day_features` (REVIEW §4: no feature changes while the holdout runs);
  - a recalibration that the coach decides to activate.
- **Practical consequence:** the window ends on 2027-02-19. A new activation after about mid-November 2026 cannot reach 90 compared days before then. Activating anything new before February effectively means deciding without a full verdict.
- Not a restart, but shown on the view as a marker: a profile change (HR max, resting HR) that differs from the model's `feature_config` (ADR 0008), and a Recompute features run inside the window.

### H9. What the view and the report must show
- The window dates.
- The frozen marks and their `source`.
- n compared, and the excluded-day counts by reason (H4).
- Every rate with n and its interval.
- The naive baseline next to ours.
- The verdict state: not enough days yet / interim / final, and pass / not met / not enough days by the rule.
- The `protocol` (this ADR).

## Coach decisions recorded (2026-11-15)
- **D1, evidence before cancelling Whoop:** option (b), "a minimum number of compared days, with today's pass marks; a mark is met when its measured value meets it, with the interval shown". The coach first chose 100 days. Then, told that Whoop ends on 2027-02-19 (no extension) and that the window holds only 103 calendar days, so 100 compared days was unlikely, the coach set the minimum to **90**.
- **D2, the window:** from the day after the ACTIVE model's test window (2026-11-09 for m15), not from the activation date. 2026-11-09 is kept.
- **D3, m15's frozen marks:** filled in by V15 from m15's own stored marks. Its status is not changed.
- **Whoop end:** 2027-02-19, no extension.

## Assumptions (flagged for review)
- **A1:** the 90-day minimum is counted on recovery days. The strain marks are judged at the same moment on the full-coverage strain days available, with their n shown.
- **A2:** recovery marked lower confidence (ADR 0008 S8) is included in the recovery comparison, as in 1a.
- **A3:** verdicts before the last Whoop day are interim; the final verdict uses the whole window.
- **A4:** the naive baseline skips days without a Whoop recovery for the day before.

## Consequences
- Stage 1b-3 implements H1–H9 from `model_version.holdout_targets` and `derived_day_metrics`, with synthetic tests. It must not read `targets.json` and must not offer a way to exclude days or edit the marks.
- `model_version.holdout_targets` (V15) is set at activation by `MetricsRepository.activate` (tested in `MetricsIT`) and, for m15, by V15 (tested in `HoldoutTargetsMigrationIT`).
- Changing a rule here applies only to versions activated after the change. A running comparison keeps the rules it started with.
