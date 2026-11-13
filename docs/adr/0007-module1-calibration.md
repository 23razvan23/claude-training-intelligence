# ADR 0007: Module 1 calibration: one feature table, monotonic models, time-based validation

- Status: accepted (Phase 1a)

## Context
The goal is Garmin-derived Recovery, Strain and Sleep scores that agree with Whoop closely enough to make the same training decisions, proven on held-out data (SPEC.md, Module 1). Fitting happens in Python; daily scoring (Phase 1b) happens in Java.

## Decisions
1. **One feature source.** Java (`metrics` package) computes `activity_load` and `day_features`. Python only reads `day_features`, fits models, and writes a `model_version` row (coefficients JSON plus metrics plus an HTML report). In Phase 1b, Java scores from the same table using those coefficients. Training and serving features cannot drift apart, and `feature_version` changes whenever a feature definition changes.
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
4. **Recovery** is a linear model of personal-baseline deviations: ln(RMSSD) z-score, resting HR deviation, overnight respiration deviation, sleep performance and yesterday's strain. Features are oriented "higher is better" and their weights constrained ≥ 0, so the model is monotonic (better HRV never lowers recovery). It uses a ridge penalty and is clipped to 0–100. SpO2 and Body Battery are added only if they have ≥ 90% coverage and improve CV MAE by ≥ 2%. A monotonic gradient-boosted challenger is always reported, but the shipped coefficients stay linear and explainable. A clear win for the challenger is noted in the report, never auto-shipped.
5. **Sleep need** = base + per-strain × yesterday's strain + per-shortfall × 7-day shortfall, all coefficients ≥ 0. Sleep performance = min(100, Garmin sleep ÷ need).
6. **Validation** is strictly time-based: the default test window is the last 20% of paired days, overridable with `--test-from`, and never shuffled. It reports MAE, Spearman, Bland–Altman and band agreement with a confusion matrix. The pass/fail targets live in `calibration/targets.json` **[configurable]**. Days with incomplete inputs are excluded and counted, never imputed.
7. **Live holdout.** Whoop stays active until 2027-02. After review, the chosen model version is frozen, and its daily scores are compared with Whoop until then: truly unseen data, the strongest go/no-go evidence.

## Consequences
- Every number the app shows will trace to a feature row, a model version and a coefficient.
- Changing a feature definition requires bumping `feature_version` and recalibrating.
