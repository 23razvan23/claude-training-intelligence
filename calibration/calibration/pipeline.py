"""The calibration run as a pure function of the day-features table (no database)."""

from __future__ import annotations

import warnings
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

from . import __version__, metrics, recovery, sleep, strain
from .config import Settings

SCHEMA_VERSION = 2  # shape of the coefficients JSON; must match calibration/contract.schema.json
STRAIN_CLIP = [0, 21]  # Whoop's strain scale (strain.predict, strain.predict_zones)
SLEEP_PERFORMANCE_CLIP = [0, 100]  # a percentage (sleep.performance)
UNKNOWN_FEATURE_CONFIG = {"hr_max": None, "hr_rest": None, "sex_assumed": None}


@dataclass
class Result:
    coefficients: dict
    metrics: dict
    passed: bool
    train_from: date | None
    train_to: date | None
    test_from: date
    test_to: date | None
    exclusions: dict
    test_days: pd.DataFrame
    notes: list[str] = field(default_factory=list)
    strain_days: pd.DataFrame | None = None


def _daily(features: pd.DataFrame) -> pd.DataFrame:
    """Reindexes to consecutive calendar days so 'yesterday' and rolling windows are exact."""
    df = features.copy()
    df["cycle_date"] = pd.to_datetime(df["cycle_date"])
    df = df.set_index("cycle_date").sort_index()
    full = pd.date_range(df.index.min(), df.index.max(), freq="D")
    return df.reindex(full)


def default_test_from(daily: pd.DataFrame, share: float) -> pd.Timestamp:
    paired = daily[daily["has_garmin_core"].fillna(False).astype(bool) & daily["whoop_recovery"].notna()]
    if len(paired) < 20:
        raise ValueError(f"Only {len(paired)} paired Garmin + Whoop days; need at least 20 to calibrate")
    return paired.index[int(len(paired) * (1 - share))]


def day_alignment(daily: pd.DataFrame, load_col: str, margin: float = 0.2) -> dict:
    """Spearman correlation of Whoop strain with our load on the previous, same and next day.
    A clear winner other than the same day means the two sources' days are misaligned."""
    load = np.log1p(daily[load_col])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # constant input -> NaN, handled below
        out = {
            "previous_day": float(daily["whoop_strain"].corr(load.shift(1), method="spearman")),
            "same_day": float(daily["whoop_strain"].corr(load, method="spearman")),
            "next_day": float(daily["whoop_strain"].corr(load.shift(-1), method="spearman")),
        }
    other = max(("previous_day", "next_day"), key=lambda k: np.nan_to_num(out[k], nan=-1))
    out["best"] = other if np.nan_to_num(out[other], nan=-1) > np.nan_to_num(out["same_day"], nan=-1) + margin else "same_day"
    return out


def _strain_coefficients(m: dict) -> dict:
    """The strain part of the coefficients contract read by daily scoring (Phase 1b)."""
    if m["form"] == "zones":
        return {
            "form": "zones",
            "a": m["a"],
            "c": m["c"],
            "zone_columns": list(strain.ZONE_COLUMNS),
            "zone_weights": m["zone_weights"],
            "min_monitored_minutes": m["min_monitored_minutes"],
            "clip": list(STRAIN_CLIP),
        }
    return {
        "form": "load",
        **{k: m[k] for k in ("load", "a", "b", "c")},
        "background_weight": m["k"] if m["background"] else 0.0,
        "uses_background": m["background"],
        "min_monitored_minutes": m["min_monitored_minutes"],
        "clip": list(STRAIN_CLIP),
    }


def _strain_inputs(coefficients: dict) -> list[str]:
    """The day_features columns the stored strain form reads (ADR 0008, S2)."""
    if coefficients["form"] == "zones":
        return list(coefficients["zone_columns"])
    return [coefficients["load"], strain.BACKGROUND] if coefficients["uses_background"] else [coefficients["load"]]


def _ranges(rows: pd.DataFrame, columns: list[str]) -> dict:
    """Spread of each input on the rows a model was fitted on. Daily scoring flags a day whose input
    falls outside [min, max] (or, more softly, [p01, p99]) as drifting beyond the training data."""
    out = {}
    for col in columns:
        v = rows[col].dropna().to_numpy(dtype=float)
        if len(v) == 0:
            raise ValueError(f"No training values for model input {col}")
        out[col] = {"min": float(v.min()), "p01": float(np.quantile(v, 0.01)), "p99": float(np.quantile(v, 0.99)), "max": float(v.max()), "n": len(v)}
    return out


def _strain_days(test: pd.DataFrame) -> pd.DataFrame:
    """Per test day: Whoop vs ours plus what might explain a miss (for the report)."""
    cols = ["whoop_strain", "strain_pred", "low_coverage"]
    extra = [c for c in ("monitored_minutes", "diag_whoop_only_workouts", "day_load_banister", *strain.ZONE_COLUMNS) if c in test]
    out = test[cols + extra].dropna(subset=["whoop_strain", "strain_pred"]).copy()
    if all(c in out for c in strain.ZONE_COLUMNS[3:]):
        out["hard_minutes"] = out[list(strain.ZONE_COLUMNS[3:])].sum(axis=1)
    return out


def run(
    features: pd.DataFrame,
    settings: Settings,
    feature_version: str,
    test_from: date | None = None,
    test_to: date | None = None,
    context: dict | None = None,
) -> Result:
    """context (optional): {"feature_config": {"hr_max", "hr_rest", "sex_assumed"}}, the profile values
    the features were built with, read from the database by the caller; recorded, never used to fit.
    test_to (optional): the last day used at all; later days are ignored, as if the data ended there."""
    if test_from is not None and test_to is not None and test_to < test_from:
        raise ValueError(f"--test-to {test_to} is before --test-from {test_from}")
    daily = _daily(features)
    if test_to is not None:
        daily = daily[daily.index <= pd.Timestamp(test_to)]
    split = pd.Timestamp(test_from) if test_from else default_test_from(daily, settings.test_share)
    is_train = daily.index < split
    notes: list[str] = []

    # --- strain -------------------------------------------------------------
    strain_model = strain.choose_and_fit(daily[is_train], settings.strain_min_monitored_minutes, complexity_min_gain=settings.strain_complexity_min_gain)
    # Day alignment is a property of the data, not of the model: always check it on workout load.
    alignment = day_alignment(daily, "day_load_banister")
    if alignment["best"] != "same_day":
        notes.append(
            f"Whoop and Garmin days look shifted by one day: Whoop strain matches the {alignment['best'].replace('_', ' ')}'s "
            f"load best (Spearman {alignment[alignment['best']]:.2f} vs {alignment['same_day']:.2f} on the same day). "
            "Check how cycle dates are assigned before trusting any result."
        )
    daily["strain_pred"] = strain.predict_model(strain_model, daily, settings.strain_min_monitored_minutes)
    spread = daily.loc[is_train, "strain_pred"].quantile(0.9) - daily.loc[is_train, "strain_pred"].quantile(0.1)
    if not spread >= 1.0:
        notes.append(
            f"The strain model is nearly flat (hard and easy days differ by only {spread:.1f} strain): heart-rate load does not explain Whoop strain in this data."
        )
    if "monitored_minutes" in daily:
        daily["low_coverage"] = daily["monitored_minutes"].fillna(0) < settings.strain_min_monitored_minutes
    else:
        daily["low_coverage"] = False
    daily["prev_strain"] = daily["strain_pred"].shift(1)

    # --- sleep --------------------------------------------------------------
    reference = float(daily.loc[is_train, "whoop_sleep_need_min"].median())
    daily["shortfall"] = sleep.add_shortfall(daily, reference, settings.sleep_shortfall_days)
    sleep_model = sleep.fit(daily[is_train])
    sleep_model.update({"shortfall_reference_min": reference, "shortfall_days": settings.sleep_shortfall_days})
    daily["sleep_need_pred"] = sleep.need(sleep_model, daily["prev_strain"].fillna(0), daily["shortfall"])
    daily["sleep_perf"] = sleep.performance(daily["sleep_minutes"], daily["sleep_need_pred"])

    # --- recovery -----------------------------------------------------------
    core_cols = [f for f, _ in recovery.CORE]
    eligible = daily["whoop_recovery"].notna() & daily["has_garmin_core"].fillna(False).astype(bool)
    exclusions = {
        "paired_days": int(eligible.sum()),
        "no_baseline_yet": int((eligible & daily["ln_rmssd_z"].isna()).sum()),
        "no_overnight_respiration": int((eligible & daily["resp_dev"].isna() & daily["ln_rmssd_z"].notna()).sum()),
        "no_load_yesterday": int((eligible & daily["prev_strain"].isna()).sum()),
    }
    rec_rows = daily[eligible].dropna(subset=core_cols)
    exclusions["used_for_recovery"] = len(rec_rows)
    rec_train = rec_rows[rec_rows.index < split]
    rec_test = rec_rows[rec_rows.index >= split]
    if len(rec_train) < 30 or len(rec_test) < 10:
        raise ValueError(f"Too few complete recovery days (train {len(rec_train)}, test {len(rec_test)})")

    chosen, selection_log = recovery.select_features(rec_train, settings.recovery_ridge_alpha, settings.optional_feature_min_coverage, settings.optional_feature_min_gain)
    chosen_cols = [f for f, _ in chosen]
    rec_train = rec_train.dropna(subset=chosen_cols)
    rec_test = rec_test.dropna(subset=chosen_cols)
    rec_model = recovery.fit(rec_train, chosen, settings.recovery_ridge_alpha)
    rec_pred = recovery.predict(rec_model, rec_test)

    challenger = recovery.fit_challenger(rec_train, chosen)
    chal_pred = np.clip(challenger.predict(rec_test[chosen_cols].to_numpy(dtype=float)), 0, 100)

    y = rec_test["whoop_recovery"].to_numpy(dtype=float)
    linear_m = {**metrics.summarize(y, rec_pred), **metrics.band_agreement(y, rec_pred, settings.green_min, settings.red_max)}
    chal_m = {**metrics.summarize(y, chal_pred), **metrics.band_agreement(y, chal_pred, settings.green_min, settings.red_max)}
    challenger_wins = chal_m["mae"] < linear_m["mae"] * (1 - settings.challenger_mae_gain) and chal_m["agreement"] > linear_m["agreement"] + settings.challenger_band_gain
    if challenger_wins:
        notes.append(
            "The gradient-boosted challenger clearly beat the linear model on the test window. "
            "It is reported here, but the shipped coefficients stay linear and explainable; "
            "consider it for a later version."
        )

    # --- test-window metrics --------------------------------------------------
    test = daily[~is_train]
    st = test[["whoop_strain", "strain_pred", "low_coverage"]].dropna(subset=["whoop_strain", "strain_pred"])
    sl = test[["whoop_sleep_need_min", "sleep_need_pred"]].dropna()
    sp = test[["whoop_sleep_perf", "sleep_perf"]].dropna()
    # Acceptance is measured on days with enough all-day HR; low-coverage days are reported
    # separately (the app flags them as low confidence).
    full = st[~st["low_coverage"].astype(bool)]
    strain_m = metrics.summarize(full["whoop_strain"], full["strain_pred"])
    result_metrics = {
        "strain": {
            **strain_m,
            "form": strain_model["form"],
            "selected": strain_model["selected"],
            "selection": strain_model["selection"],
            "load": strain_model.get("load"),
            "full_coverage": metrics.summarize(st.loc[~st["low_coverage"].astype(bool), "whoop_strain"], st.loc[~st["low_coverage"].astype(bool), "strain_pred"]),
            "low_coverage": metrics.summarize(st.loc[st["low_coverage"].astype(bool), "whoop_strain"], st.loc[st["low_coverage"].astype(bool), "strain_pred"]),
            "candidates": {k: v["cv_mae"] for k, v in strain_model["candidates"].items()},
            "background_coverage": strain_model["background_coverage"],
            "alignment": alignment,
        },
        "sleep_need": metrics.summarize(sl["whoop_sleep_need_min"], sl["sleep_need_pred"]),
        "sleep_performance": metrics.summarize(sp["whoop_sleep_perf"], sp["sleep_perf"]),
        "recovery": {**linear_m, "feature_selection": selection_log},
        "recovery_challenger": {**chal_m, "clearly_better": bool(challenger_wins)},
        "targets": {
            "strain_mae_max": settings.strain_mae_max,
            "strain_spearman_min": settings.strain_spearman_min,
            "recovery_band_agreement_min": settings.recovery_band_agreement_min,
            "recovery_green_red_max": settings.recovery_green_red_max,
        },
    }
    checks = {
        "strain_mae": strain_m.get("mae", float("inf")) <= settings.strain_mae_max,
        "strain_spearman": np.nan_to_num(strain_m.get("spearman", float("nan")), nan=-1.0) >= settings.strain_spearman_min,
        "recovery_band_agreement": linear_m["agreement"] >= settings.recovery_band_agreement_min,
        "recovery_green_red": linear_m["green_red_rate"] <= settings.recovery_green_red_max,
    }
    result_metrics["checks"] = checks

    strain_coefficients = _strain_coefficients(strain_model)
    feature_config = {**UNKNOWN_FEATURE_CONFIG, **((context or {}).get("feature_config") or {})}
    coefficients = {
        "schema_version": SCHEMA_VERSION,
        "calibration_version": __version__,
        "feature_version": feature_version,
        "feature_config": {k: feature_config[k] for k in UNKNOWN_FEATURE_CONFIG},
        "strain": strain_coefficients,
        "sleep": {
            **{k: sleep_model[k] for k in ("base_min", "per_strain_min", "per_shortfall", "shortfall_reference_min", "shortfall_days")},
            "performance_clip": list(SLEEP_PERFORMANCE_CLIP),
        },
        "recovery": {k: rec_model[k] for k in ("intercept", "terms", "clip")},
        "bands": {"green_min": settings.green_min, "red_max": settings.red_max},
        "training_ranges": {
            "strain": _ranges(daily.loc[strain_model["train_index"]], _strain_inputs(strain_coefficients)),
            "sleep": _ranges(daily.loc[sleep_model["train_index"]], ["prev_strain", "shortfall"]),
            "recovery": _ranges(rec_train, chosen_cols),
        },
    }

    test_days = pd.DataFrame(
        {
            "whoop_recovery": y,
            "recovery": rec_pred,
            "challenger": chal_pred,
        },
        index=rec_test.index,
    ).join(recovery.contributions(rec_model, rec_test))
    test_days = test_days.join(test[["whoop_strain", "strain_pred", "whoop_sleep_need_min", "sleep_need_pred"]], how="left")
    strain_days = _strain_days(test)

    train_idx = daily.index[is_train & (daily["whoop_strain"].notna() | daily["whoop_recovery"].notna())]
    return Result(
        coefficients=coefficients,
        metrics=result_metrics,
        passed=all(checks.values()),
        train_from=train_idx.min().date() if len(train_idx) else None,
        train_to=train_idx.max().date() if len(train_idx) else None,
        test_from=split.date(),
        test_to=rec_test.index.max().date() if len(rec_test) else None,
        exclusions=exclusions,
        test_days=test_days,
        notes=notes,
        strain_days=strain_days,
    )
