"""Recovery (0–100) as a monotonic linear model of personal-baseline deviations.

Every feature is oriented so that "higher is better" (e.g. −resting-HR deviation), then
standardised on the training data. The weights are fitted by ridge-penalised least squares
with weights ≥ 0, so better HRV can never lower recovery, higher resting HR can never raise
it, and so on. Score = intercept + Σ weight · (oriented value − center) / scale, clipped to
0–100; each term is that feature's contribution in points.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.optimize import lsq_linear
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.model_selection import TimeSeriesSplit

CORE = [("ln_rmssd_z", 1), ("rhr_dev", -1), ("resp_dev", -1), ("sleep_perf", 1), ("prev_strain", -1)]
OPTIONAL = [("sleep_spo2", 1), ("body_battery_high", 1)]


def fit(train: pd.DataFrame, features: list[tuple[str, int]], alpha: float) -> dict:
    cols = [f for f, _ in features]
    signs = np.array([s for _, s in features], dtype=float)
    x = train[cols].to_numpy(dtype=float) * signs
    y = train["whoop_recovery"].to_numpy(dtype=float)
    center = x.mean(axis=0)
    scale = x.std(axis=0, ddof=0)
    scale[scale == 0] = 1.0
    xs = (x - center) / scale
    k = xs.shape[1]
    a = np.vstack([np.column_stack([xs, np.ones(len(xs))]), np.column_stack([np.sqrt(alpha) * np.eye(k), np.zeros(k)])])
    t = np.concatenate([y, np.zeros(k)])
    res = lsq_linear(a, t, bounds=(np.r_[np.zeros(k), -np.inf], np.r_[np.full(k, np.inf), np.inf]))
    w, b = res.x[:k], res.x[k]
    return {
        "intercept": float(b),
        "terms": [
            {"feature": f, "sign": int(s), "weight": float(wi), "center": float(c), "scale": float(sc)}
            for (f, s), wi, c, sc in zip(features, w, center, scale, strict=True)
        ],
        "clip": [0, 100],
        "n": len(train),
    }


def contributions(model: dict, rows: pd.DataFrame) -> pd.DataFrame:
    out = {}
    for t in model["terms"]:
        out[t["feature"]] = t["weight"] * (t["sign"] * rows[t["feature"]].to_numpy(dtype=float) - t["center"]) / t["scale"]
    return pd.DataFrame(out, index=rows.index)


def predict(model: dict, rows: pd.DataFrame) -> np.ndarray:
    raw = model["intercept"] + contributions(model, rows).sum(axis=1).to_numpy()
    return np.clip(raw, *model["clip"])


def cv_mae(data: pd.DataFrame, features: list[tuple[str, int]], alpha: float, folds: int = 5) -> float:
    errors = []
    for tr, te in TimeSeriesSplit(n_splits=min(folds, max(2, len(data) // 25))).split(data):
        m = fit(data.iloc[tr], features, alpha)
        errors.append(np.abs(predict(m, data.iloc[te]) - data["whoop_recovery"].iloc[te]).mean())
    return float(np.mean(errors))


def select_features(train: pd.DataFrame, alpha: float, min_coverage: float, min_gain: float) -> tuple[list, list]:
    """Starts from the core features; adds an optional one only if it is present on enough
    days and lowers time-series CV MAE by at least min_gain (relative). Returns (features, log)."""
    features = list(CORE)
    log = []
    base = train.dropna(subset=[f for f, _ in features])
    best = cv_mae(base, features, alpha)
    log.append({"features": [f for f, _ in features], "cv_mae": best, "added": None})
    for opt in OPTIONAL:
        coverage = base[opt[0]].notna().mean() if len(base) else 0
        if coverage < min_coverage:
            log.append({"candidate": opt[0], "coverage": float(coverage), "added": False, "reason": "coverage"})
            continue
        trial = features + [opt]
        rows = train.dropna(subset=[f for f, _ in trial])
        score = cv_mae(rows, trial, alpha)
        added = score < best * (1 - min_gain)
        log.append({"candidate": opt[0], "coverage": float(coverage), "cv_mae": score, "added": bool(added)})
        if added:
            features, best = trial, score
    return features, log


def fit_challenger(train: pd.DataFrame, features: list[tuple[str, int]]) -> HistGradientBoostingRegressor:
    cols = [f for f, _ in features]
    model = HistGradientBoostingRegressor(
        max_iter=300,
        learning_rate=0.05,
        max_leaf_nodes=15,
        min_samples_leaf=10,
        monotonic_cst=[s for _, s in features],
        random_state=0,
    )
    model.fit(train[cols].to_numpy(dtype=float), train["whoop_recovery"].to_numpy(dtype=float))
    return model
