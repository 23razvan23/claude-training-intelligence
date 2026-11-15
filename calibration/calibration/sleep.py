"""Sleep need and sleep performance.

need = base + per_strain · (yesterday's strain) + per_shortfall · (7-day shortfall), with
every coefficient ≥ 0, fitted to Whoop's sleep need. The shortfall sums, over the previous
7 nights, how far Garmin-measured sleep fell below a reference need (the train median of
Whoop's need). Sleep performance = min(100, sleep ÷ need).
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.optimize import lsq_linear


def add_shortfall(daily: pd.DataFrame, reference_min: float, days: int) -> pd.Series:
    """daily is indexed by consecutive calendar dates. Missing nights add no shortfall."""
    deficit = (reference_min - daily["sleep_minutes"]).clip(lower=0).fillna(0)
    return deficit.shift(1).rolling(days, min_periods=1).sum().fillna(0)


def fit(train: pd.DataFrame) -> dict:
    rows = train[["whoop_sleep_need_min", "prev_strain", "shortfall"]].dropna()
    if len(rows) < 30:
        raise ValueError("Not enough days with Whoop sleep need to fit sleep")
    a = np.column_stack([np.ones(len(rows)), rows["prev_strain"], rows["shortfall"]])
    res = lsq_linear(a, rows["whoop_sleep_need_min"].to_numpy(dtype=float), bounds=([0, 0, 0], [900, 60, 1]))
    base, per_strain, per_shortfall = res.x
    return {"base_min": float(base), "per_strain_min": float(per_strain), "per_shortfall": float(per_shortfall), "n": len(rows), "train_index": rows.index}


def need(params: dict, prev_strain, shortfall) -> np.ndarray:
    return params["base_min"] + params["per_strain_min"] * np.asarray(prev_strain, dtype=float) + params["per_shortfall"] * np.asarray(shortfall, dtype=float)


def performance(sleep_minutes, need_min) -> np.ndarray:
    return np.clip(100 * np.asarray(sleep_minutes, dtype=float) / np.asarray(need_min, dtype=float), 0, 100)
