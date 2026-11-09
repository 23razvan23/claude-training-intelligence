"""Strain (0–21) from daily HR load: strain = c + a·ln(1 + b·load).

The spec's form is a·ln(1 + b·load). The intercept c is added because all-day HR is not
imported yet: without it, a rest day would score 0 while Whoop gives daily life a
baseline strain (ADR 0007).
"""

from __future__ import annotations

import numpy as np
from scipy.optimize import curve_fit
from sklearn.model_selection import TimeSeriesSplit

LOAD_COLUMNS = ("day_load_banister", "day_load_edwards")


def curve(load, a, b, c):
    return c + a * np.log1p(b * np.asarray(load, dtype=float))


def fit(load: np.ndarray, strain: np.ndarray) -> dict:
    load = np.asarray(load, dtype=float)
    strain = np.asarray(strain, dtype=float)
    scale = max(float(np.median(load[load > 0])) if (load > 0).any() else 1.0, 1e-3)
    p0 = [4.0, 1.0 / scale, float(np.percentile(strain, 10))]
    (a, b, c), _ = curve_fit(curve, load, strain, p0=p0, bounds=([0, 1e-6, 0], [21, 100, 21]), maxfev=50_000)
    return {"a": float(a), "b": float(b), "c": float(c)}


def predict(params: dict, load) -> np.ndarray:
    return np.clip(curve(load, params["a"], params["b"], params["c"]), 0, 21)


def cv_mae(load: np.ndarray, strain: np.ndarray, folds: int = 5) -> float:
    load = np.asarray(load, dtype=float)
    strain = np.asarray(strain, dtype=float)
    errors = []
    for tr, te in TimeSeriesSplit(n_splits=min(folds, max(2, len(load) // 20))).split(load):
        params = fit(load[tr], strain[tr])
        errors.append(np.abs(predict(params, load[te]) - strain[te]).mean())
    return float(np.mean(errors))


def choose_and_fit(train) -> dict:
    """Fits both load definitions; keeps the one with the lower time-series CV MAE on train."""
    candidates = {}
    for col in LOAD_COLUMNS:
        rows = train[[col, "whoop_strain"]].dropna()
        if len(rows) < 30:
            continue
        candidates[col] = {
            "cv_mae": cv_mae(rows[col].to_numpy(), rows["whoop_strain"].to_numpy()),
            "params": fit(rows[col].to_numpy(), rows["whoop_strain"].to_numpy()),
            "n": len(rows),
        }
    if not candidates:
        raise ValueError("Not enough days with both a training load and a Whoop strain to fit strain")
    best = min(candidates, key=lambda k: candidates[k]["cv_mae"])
    return {"load": best, **candidates[best]["params"], "candidates": candidates}
