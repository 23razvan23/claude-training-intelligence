"""A synthetic day-features table where the true relationships are known."""

from __future__ import annotations

import numpy as np
import pandas as pd

TRUE_STRAIN = {"a": 3.5, "b": 0.03, "c": 6.0, "k": 0.5}


def make(days: int = 300, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    dates = pd.date_range("2026-01-01", periods=days, freq="D")
    load = np.where(rng.random(days) < 0.25, 0.0, rng.gamma(2.0, 40.0, days))
    background = rng.gamma(3.0, 10.0, days)  # daily-life load from all-day HR
    total = load + TRUE_STRAIN["k"] * background
    strain = TRUE_STRAIN["c"] + TRUE_STRAIN["a"] * np.log1p(TRUE_STRAIN["b"] * total) + rng.normal(0, 0.6, days)
    z = rng.normal(0, 1, days)
    rhr_dev = rng.normal(0, 2, days)
    resp_dev = rng.normal(0, 0.5, days)
    sleep_min = rng.normal(430, 40, days)
    prev_strain = np.r_[np.nan, strain[:-1]]
    need = 450 + 6 * np.nan_to_num(prev_strain, nan=10)
    perf = np.clip(100 * sleep_min / need, 0, 100)
    recovery = np.clip(55 + 12 * z - 3 * rhr_dev - 6 * resp_dev + 0.3 * (perf - 90) - 0.8 * (np.nan_to_num(prev_strain, nan=10) - 10) + rng.normal(0, 6, days), 1, 99)
    df = pd.DataFrame(
        {
            "cycle_date": dates.date,
            "ln_rmssd": 4 + 0.2 * z,
            "ln_rmssd_z": z,
            "rhr": 48 + rhr_dev,
            "rhr_dev": rhr_dev,
            "sleep_respiration": 13 + resp_dev,
            "resp_dev": resp_dev,
            "sleep_spo2": 96 + rng.normal(0, 1, days),
            "body_battery_high": rng.integers(40, 100, days).astype(float),
            "sleep_minutes": sleep_min,
            "day_load_banister": load,
            "day_load_edwards": load * 0.9 + rng.normal(0, 15, days),
            "background_load": background,
            "monitored_minutes": np.full(days, 1300),
            "has_garmin_core": True,
            "whoop_recovery": np.round(recovery),
            "whoop_strain": np.round(strain, 1),
            "whoop_sleep_need_min": np.round(need),
            "whoop_sleep_perf": np.round(perf),
        }
    )
    df.loc[:13, "ln_rmssd_z"] = np.nan  # baseline warm-up, like real data
    return df


TRUE_ZONE_WEIGHTS = [0.0005, 0.01, 0.03, 0.08, 0.15, 0.25]


def make_zones(days: int = 300, seed: int = 11) -> pd.DataFrame:
    """Like make(), but Whoop strain follows a whole-day zone model, including workouts
    Garmin never recorded (they show up in zone minutes but not in workout load)."""
    rng = np.random.default_rng(seed)
    df = make(days, seed)
    zones = np.column_stack(
        [
            rng.normal(1150, 60, days),  # rest, sleep, quiet daily life
            rng.gamma(3.0, 25.0, days),  # light activity
            rng.gamma(2.0, 10.0, days),
            rng.gamma(1.5, 8.0, days),
            rng.gamma(1.2, 5.0, days),
            rng.gamma(1.0, 2.0, days),
        ]
    )
    unrecorded = rng.random(days) < 0.15  # Whoop-only workouts
    zones[unrecorded, 3] += rng.gamma(4.0, 8.0, unrecorded.sum())
    for z in range(6):
        df[f"zone_min_{z}"] = zones[:, z]
    df["diag_whoop_only_workouts"] = unrecorded.astype(int)
    recorded_hard = zones[:, 2:].sum(axis=1) - np.where(unrecorded, zones[:, 3], 0)
    df["day_load_banister"] = recorded_hard * 1.5 + rng.normal(0, 5, days).clip(min=0)
    df["day_load_edwards"] = recorded_hard * 2.0
    total = zones @ np.asarray(TRUE_ZONE_WEIGHTS)
    df["whoop_strain"] = np.round(np.clip(2.5 + 4.0 * np.log1p(total) + rng.normal(0, 0.5, days), 0, 21), 1)
    return df
