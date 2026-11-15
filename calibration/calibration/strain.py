"""Strain (0–21) from heart-rate load. Two model forms compete on time-series CV:

load form   strain = c + a·ln(1 + b·(workout load + k·background load))
zones form  strain = c + a·ln(1 + Σ_z w_z · minutes in HR-reserve zone z)

The spec's form is a·ln(1 + b·load). The zones form is the same saturating curve over a
whole-day load in which each zone minute has its own weight. Weights are ≥ 0 and
non-decreasing with intensity (w_0 ≤ w_1 ≤ … ≤ w_5), so a harder minute never counts less.
The zone minutes come from one timeline per day: per-second HR in recorded workouts plus
all-day HR elsewhere, so workouts Garmin never recorded still count at their real
intensity. The intercept c covers what neither sees (ADR 0007).
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.optimize import curve_fit
from sklearn.model_selection import TimeSeriesSplit

LOAD_COLUMNS = ("day_load_banister", "day_load_edwards")
BACKGROUND = "background_load"
ZONE_COLUMNS = tuple(f"zone_min_{z}" for z in range(6))
ZONES_NAME = "whole-day HR zones"


# ---------------------------------------------------------------- load form


def _curve(x, a, b, c, k):
    act, bg = x
    return c + a * np.log1p(b * (act + k * bg))


def fit(load, strain, background=None) -> dict:
    load = np.asarray(load, dtype=float)
    strain = np.asarray(strain, dtype=float)
    bg = np.zeros_like(load) if background is None else np.asarray(background, dtype=float)
    total = load + bg
    scale = max(float(np.median(total[total > 0])) if (total > 0).any() else 1.0, 1e-3)
    if background is None:
        f = lambda x, a, b, c: _curve(x, a, b, c, 0.0)  # noqa: E731
        p0, lo, hi = [4.0, 1.0 / scale, float(np.percentile(strain, 10))], [0, 1e-6, 0], [21, 100, 21]
    else:
        f = _curve
        p0, lo, hi = [4.0, 1.0 / scale, float(np.percentile(strain, 10)), 1.0], [0, 1e-6, 0, 0], [21, 100, 21, 20]
    params, _ = curve_fit(f, (load, bg), strain, p0=p0, bounds=(lo, hi), maxfev=50_000)
    return {"a": float(params[0]), "b": float(params[1]), "c": float(params[2]), "k": float(params[3]) if background is not None else 0.0}


def predict(params: dict, load, background=None) -> np.ndarray:
    load = np.asarray(load, dtype=float)
    bg = np.zeros_like(load) if background is None or params.get("k", 0) == 0 else np.asarray(background, dtype=float)
    return np.clip(_curve((load, bg), params["a"], params["b"], params["c"], params.get("k", 0.0)), 0, 21)


# ---------------------------------------------------------------- zones form


def _zone_curve(z, a, c, *increments):
    weights = np.cumsum(increments)  # increments ≥ 0 ⇒ weights ≥ 0 and non-decreasing
    return c + a * np.log1p(np.asarray(z, dtype=float) @ weights)


def fit_zones(zone_minutes, strain) -> dict:
    z = np.asarray(zone_minutes, dtype=float)
    strain = np.asarray(strain, dtype=float)
    # Start near "a quiet day ≈ 1, a hard hour ≈ 30" in the log's argument.
    p0 = [4.0, float(np.percentile(strain, 10)), 1e-3, 0.02, 0.05, 0.1, 0.2, 0.3]
    lo = [0, 0, 0, 0, 0, 0, 0, 0]
    hi = [21, 21, 1, 5, 5, 5, 5, 5]
    params, _ = curve_fit(_zone_curve, z, strain, p0=p0, bounds=(lo, hi), maxfev=100_000)
    return {"a": float(params[0]), "c": float(params[1]), "zone_weights": [float(w) for w in np.cumsum(params[2:])]}


def predict_zones(params: dict, zone_minutes) -> np.ndarray:
    z = np.asarray(zone_minutes, dtype=float)
    return np.clip(params["c"] + params["a"] * np.log1p(z @ np.asarray(params["zone_weights"])), 0, 21)


# ---------------------------------------------------------------- selection


def _cv(fit_fn, predict_fn, x, strain, folds: int = 5) -> float:
    errors = []
    for tr, te in TimeSeriesSplit(n_splits=min(folds, max(2, len(strain) // 20))).split(strain):
        p = fit_fn(x[tr], strain[tr])
        errors.append(np.abs(predict_fn(p, x[te]) - strain[te]).mean())
    return float(np.mean(errors))


def cv_mae(load, strain, background=None, folds: int = 5) -> float:
    load = np.asarray(load, dtype=float)
    strain = np.asarray(strain, dtype=float)
    if background is None:
        return _cv(lambda x, y: fit(x, y), lambda p, x: predict(p, x), load, strain, folds)
    x = np.column_stack([load, np.asarray(background, dtype=float)])
    return _cv(lambda x, y: fit(x[:, 0], y, x[:, 1]), lambda p, x: predict(p, x[:, 0], x[:, 1]), x, strain, folds)


def cv_mae_zones(zone_minutes, strain, folds: int = 5) -> float:
    return _cv(fit_zones, predict_zones, np.asarray(zone_minutes, dtype=float), np.asarray(strain, dtype=float), folds)


def background_ok(df: pd.DataFrame, min_minutes: int) -> pd.Series:
    """Days whose all-day HR is trustworthy: background computed, with enough watch coverage."""
    if BACKGROUND not in df or "monitored_minutes" not in df:
        return pd.Series(False, index=df.index)
    return df[BACKGROUND].notna() & (df["monitored_minutes"].fillna(0) >= min_minutes)


def has_zones(df: pd.DataFrame) -> pd.Series:
    if not all(c in df for c in ZONE_COLUMNS):
        return pd.Series(False, index=df.index)
    return df[list(ZONE_COLUMNS)].notna().all(axis=1)


def select(candidates: dict, base: list[str], complex_: list[str], min_gain: float) -> tuple[str, str]:
    """Parsimony rule: take the best base candidate; switch to a more complex form only if its
    time-series CV MAE is at least min_gain (relative) lower. Returns (name, explanation).

    Adopted 2026-11-13 after the zone model (8 parameters) won training CV by 4.8% but was
    clearly worse on the held-out days (MAE 2.38 vs 1.91): a small CV gain does not justify a
    much more flexible model. The recovery challenger already followed this rule (ADR 0007)."""
    best_base = min(base, key=lambda k: candidates[k]["cv_mae"])
    if not complex_:
        return best_base, f"{best_base}: lowest CV MAE among the base candidates."
    best_complex = min(complex_, key=lambda k: candidates[k]["cv_mae"])
    b, c = candidates[best_base]["cv_mae"], candidates[best_complex]["cv_mae"]
    gain = (b - c) / b if b > 0 else 0.0
    if gain >= min_gain:
        return best_complex, f"{best_complex}: CV MAE {c:.2f} beats {best_base} ({b:.2f}) by {100 * gain:.0f}% (≥ {100 * min_gain:.0f}% required)."
    return best_base, (
        f"{best_base}: the more flexible {best_complex} is only {100 * gain:.0f}% better in CV ({c:.2f} vs {b:.2f}); "
        f"≥ {100 * min_gain:.0f}% is required to prefer it."
    )


def choose_and_fit(train: pd.DataFrame, min_monitored_minutes: int = 600, min_background_share: float = 0.8, complexity_min_gain: float = 0.10) -> dict:
    """Fits every candidate on the same training days and selects with the parsimony rule.

    Base form: the spec's daily strain, i.e. workout load plus low-level daily HR load when
    Garmin provides all-day HR (so the background term is part of the base whenever most
    days have enough watch coverage). More complex form: whole-day HR zones. Workout-load-only
    fits are still reported when all-day HR exists."""
    base = train.dropna(subset=["whoop_strain", *LOAD_COLUMNS])
    if len(base) < 30:
        raise ValueError("Not enough days with both a training load and a Whoop strain to fit strain")
    ok = background_ok(base, min_monitored_minutes)
    use_all_day = ok.mean() >= min_background_share
    rows = base[ok] if use_all_day else base
    zones_available = use_all_day and has_zones(rows).mean() >= 0.95
    if zones_available:
        rows = rows[has_zones(rows)]
    y = rows["whoop_strain"].to_numpy(dtype=float)

    candidates = {}
    for col in LOAD_COLUMNS:
        for with_bg in (False, True) if use_all_day else (False,):
            bg = rows[BACKGROUND].to_numpy() if with_bg else None
            candidates[f"{col} + background" if with_bg else col] = {
                "form": "load",
                "load": col,
                "background": with_bg,
                "cv_mae": cv_mae(rows[col].to_numpy(), y, bg),
                "params": fit(rows[col].to_numpy(), y, bg),
                "n": len(rows),
            }
    if zones_available:
        z = rows[list(ZONE_COLUMNS)].to_numpy(dtype=float)
        candidates[ZONES_NAME] = {"form": "zones", "cv_mae": cv_mae_zones(z, y), "params": fit_zones(z, y), "n": len(rows)}

    base = [k for k, v in candidates.items() if v["form"] == "load" and v["background"] == use_all_day]
    complex_ = [ZONES_NAME] if ZONES_NAME in candidates else []
    best, why = select(candidates, base, complex_, complexity_min_gain)
    chosen = candidates[best]
    model = {
        "form": chosen["form"],
        **chosen["params"],
        "candidates": candidates,
        "selected": best,
        "selection": why,
        "background_coverage": float(ok.mean()),
        "min_monitored_minutes": min_monitored_minutes,
        "train_index": rows.index,  # the days every candidate was fitted on (for training_ranges)
    }
    if chosen["form"] == "load":
        model.update({"load": chosen["load"], "background": chosen["background"]})
    return model


def predict_model(model: dict, df: pd.DataFrame, min_monitored_minutes: int) -> pd.Series:
    """Strain for every day the chosen model can score (NaN otherwise). The zones form also
    scores days with little all-day HR; those are flagged in the report, not dropped."""
    if model["form"] == "zones":
        usable = has_zones(df)
        pred = predict_zones(model, df[list(ZONE_COLUMNS)].fillna(0).to_numpy(dtype=float))
    elif model.get("background"):
        # Days with little all-day HR are scored too (their background is partial) and flagged
        # as low confidence by the caller; acceptance metrics use full-coverage days only.
        usable = df[model["load"]].notna() & df[BACKGROUND].notna()
        pred = predict(model, df[model["load"]].fillna(0), df[BACKGROUND].fillna(0))
    else:
        usable = df[model["load"]].notna()
        pred = predict(model, df[model["load"]].fillna(0))
    return pd.Series(np.where(usable, pred, np.nan), index=df.index)
