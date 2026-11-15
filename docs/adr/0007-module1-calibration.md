# ADR 0007: Module 1 calibration: one feature table, monotonic models, time-based validation

- Status: accepted (Phase 1a)

## Context
The goal is Garmin-derived Recovery, Strain and Sleep scores that agree with Whoop closely enough to make the same training decisions, proven on held-out data (SPEC.md, Module 1). Fitting happens in Python; daily scoring (Phase 1b) happens in Java.

## Decisions
1. **One feature source.** Java (`metrics` package) computes `activity_load` and `day_features`. Python only reads `day_features`, fits models, and writes a `model_version` row (coefficients JSON plus metrics plus an HTML report). In Phase 1b, Java scores from the same table using those coefficients. Training and serving features cannot drift apart, and `feature_version` changes whenever a feature definition changes. How Java turns the coefficients into scores (the values derived inside the calibration, gaps, rounding) is fixed in **ADR 0008 (scoring contract)**. The coefficients JSON itself is versioned (`schema_version` 2, since calibration 0.6.0) and its shape is `calibration/contract.schema.json`. Besides the model parameters it records the clip limits, the training range of every model input (for drift flags) and the profile values the features were built with (`feature_config`); see ADR 0008, Context.
2. **Load.** Per activity, Banister TRIMP and Edwards zone-TRIMP are computed from per-second HR, using HRmax, HRrest and sex from the profile. Without HRmax, load is *unknown* (null), never zero. Calibration picks whichever load definition has the lower time-series cross-validated MAE.
3. **Strain = c + a·ln(1 + b·(workout load + k·background load)).**
   - Background load is the Banister load from **all-day HR** (Garmin monitoring FIT files, table `monitoring_hr`) outside workout windows. Each sample is assigned to its wake-to-wake cycle.
   - It was added after the first aligned calibration: recovery passed (88% band agreement) but strain missed (MAE 2.34 vs ≤ 1.5), because Whoop's day strain counts daily life and the model saw only workouts.
   - The weight k ≥ 0 is fitted. Days with < 600 min of all-day HR are not used for the background model **[configurable]**. The background term is chosen only if it lowers time-series CV MAE; otherwise the model falls back to workout load.
   - The intercept `c` covers what neither load sees.
   - Feature version `f2`.
   - **Whole-day zones form (f3, 2026-11-13):** strain = c + a·ln(1 + Σ_z w_z · minutes in heart-rate-reserve zone z).
     - One timeline per day: per-second HR in recorded workouts, all-day HR elsewhere.
     - Weights are ≥ 0 and non-decreasing with intensity, fitted as non-negative increments.
     - Motivation: at m9, strain plateaued at MAE 1.91 with a background weight of only 0.11. Of 466 Whoop workouts, 66 had no Garmin activity, and the workout and background loads were on different scales.
     - It competes on time-series CV with the load forms and is used only if it wins.
     - It also scores days with little all-day HR; the report shows their error separately as lower confidence.
     - Zone floors: `app.metrics.hrr-zone-floors` **[configurable]**.
   - **Selection rule (2026-11-13, informed by a test result):**
     - Base form: the spec's daily strain, i.e. workout load plus daily HR load when all-day HR is available.
     - A more complex form (the zones) must beat the base by **≥ 10%** in training CV **[configurable: `strain_complexity_min_gain`]**, the same rule the recovery challenger follows.
     - Why: at m12, the zone model won CV by 4.8% (1.772 vs 1.862) but was clearly worse on held-out days (MAE 2.38 vs 1.91). Its weights collapsed into a step (50–80% HRR all equal), a sign of overfitting.
     - Because this rule was adopted after seeing a test result, that test window is no longer fully unbiased evidence for the choice. The live holdout (item 7) is the clean check.
4. **Recovery** is a linear model of personal-baseline deviations: ln(RMSSD) z-score, resting HR deviation, overnight respiration deviation, sleep performance and yesterday's strain. Features are oriented "higher is better" and their weights constrained ≥ 0, so the model is monotonic (better HRV never lowers recovery). It uses a ridge penalty and is clipped to 0–100. SpO2 and Body Battery are added only if they have ≥ 90% coverage and improve CV MAE by ≥ 2%. A monotonic gradient-boosted challenger is always reported, but the shipped coefficients stay linear and explainable. A clear win for the challenger is noted in the report, never auto-shipped.
5. **Sleep need** = base + per-strain × yesterday's strain + per-shortfall × 7-day shortfall, all coefficients ≥ 0. Sleep performance = min(100, Garmin sleep ÷ need).
6. **Strain targets revised by the coach (2026-11-13).** After the 1a iterations, strain MAE ≤ 2.0 **and** Spearman ≥ 0.80, evaluated on days with ≥ 600 min of all-day HR. Days with less are scored, reported separately and flagged as low confidence in the app: a watch that was off cannot support an acceptance decision. Recovery targets are unchanged. The spec allows revisiting targets after the first run; strain mainly feeds the displayed day strain, while training decisions rest on recovery and our own load.
7. **Validation** is strictly time-based: the default test window is the last 20% of paired days, overridable with `--test-from`; `--test-to` ignores every day after it, so an earlier run's windows can be reproduced exactly. Without `--test-from`, the default split is then the last 20% of paired days up to `--test-to` (assumption, R-02); give both dates to keep the training window, and so the coefficients, fixed. It is never shuffled. It reports MAE, Spearman, Bland–Altman and band agreement with a confusion matrix. The pass/fail targets live in `calibration/targets.json` **[configurable]**. Days with incomplete inputs are excluded and counted, never imputed.
8. **Live holdout.** Whoop stays active until 2027-02. After review, the coach **activates** the chosen model version (`POST /api/models/{id}/activate`). Only versions that met their targets qualify, there is one ACTIVE version per athlete, and the previous one is RETIRED. Its daily scores are then compared with Whoop until 2027-02: truly unseen data, the strongest go/no-go evidence.

## Consequences
- Every number the app shows will trace to a feature row, a model version and a coefficient.
- Changing a feature definition requires bumping `feature_version` and recalibrating.
