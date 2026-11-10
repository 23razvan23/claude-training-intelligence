"""Strain (0–21) from daily HR load.

    strain = c + a·ln(1 + b·(workout load + k·background load))

The spec's form is a·ln(1 + b·load). Background load is the Banister load from all-day
heart rate outside workouts: daily life, which Whoop's day strain also counts. Its weight
k ≥ 0 is fitted. The intercept c covers what neither load sees (ADR 0007). The background
term is used only when it lowers time-series CV MAE and enough days have all-day HR.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.optimize import curve_fit
from sklearn.model_selection import TimeSeriesSplit

LOAD_COLUMNS = ("day_load_banister", "day_load_edwards")
BACKGROUND = "background_load"


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


def cv_mae(load, strain, background=None, folds: int = 5) -> float:
    load = np.asarray(load, dtype=float)
    strain = np.asarray(strain, dtype=float)
    bg = None if background is None else np.asarray(background, dtype=float)
    errors = []
    for tr, te in TimeSeriesSplit(n_splits=min(folds, max(2, len(load) // 20))).split(load):
        p = fit(load[tr], strain[tr], None if bg is None else bg[tr])
        errors.append(np.abs(predict(p, load[te], None if bg is None else bg[te]) - strain[te]).mean())
    return float(np.mean(errors))


def background_ok(df: pd.DataFrame, min_minutes: int) -> pd.Series:
    """Days whose background load is trustworthy: computed, with enough all-day HR coverage."""
    if BACKGROUND not in df or "monitored_minutes" not in df:
        return pd.Series(False, index=df.index)
    return df[BACKGROUND].notna() & (df["monitored_minutes"].fillna(0) >= min_minutes)


def choose_and_fit(train: pd.DataFrame, min_monitored_minutes: int = 600, min_background_share: float = 0.8) -> dict:
    """Fits each workout-load definition with and without the background term on the same
    days, and keeps the candidate with the lowest time-series CV MAE."""
    base = train.dropna(subset=["whoop_strain", *LOAD_COLUMNS])
    if len(base) < 30:
        raise ValueError("Not enough days with both a training load and a Whoop strain to fit strain")
    ok = background_ok(base, min_monitored_minutes)
    use_bg = ok.mean() >= min_background_share
    rows = base[ok] if use_bg else base
    candidates = {}
    for col in LOAD_COLUMNS:
        for with_bg in (False, True) if use_bg else (False,):
            name = f"{col} + background" if with_bg else col
            bg = rows[BACKGROUND].to_numpy() if with_bg else None
            candidates[name] = {
                "load": col,
                "background": with_bg,
                "cv_mae": cv_mae(rows[col].to_numpy(), rows["whoop_strain"].to_numpy(), bg),
                "params": fit(rows[col].to_numpy(), rows["whoop_strain"].to_numpy(), bg),
                "n": len(rows),
            }
    best = min(candidates, key=lambda k: candidates[k]["cv_mae"])
    chosen = candidates[best]
    return {
        "load": chosen["load"],
        "background": chosen["background"],
        **chosen["params"],
        "candidates": candidates,
        "background_coverage": float(ok.mean()),
        "min_monitored_minutes": min_monitored_minutes,
    }
