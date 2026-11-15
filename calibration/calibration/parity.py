"""Reference daily scoring: ADR 0008 rules S1-S8 applied to a day_features table.

The app never runs this. It produces the expected answers for the Java/Python parity test (R-03):
stage 1b-1's Java scorer must reproduce, for the same rows and coefficients, every value scored here.
It reuses the calibration's own functions (strain.predict_model, sleep.*, recovery.contributions), so
the expected answers are what the calibrated model does; only the gap rules that pandas left implicit
(ADR 0008, S6 warning) are spelled out.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal

import numpy as np
import pandas as pd

from . import metrics, recovery, sleep, strain

# S6: one reason per missing recovery input, in this order.
REASON_FOR_INPUT = {
    "ln_rmssd_z": "NO_HRV",
    "rhr_dev": "NO_RESTING_HR",
    "resp_dev": "NO_RESPIRATION",
    "sleep_perf": "NO_SLEEP",
    "prev_strain": "NO_STRAIN_YESTERDAY",
    "sleep_spo2": "NO_SPO2",
    "body_battery_high": "NO_BODY_BATTERY",
}
REASON_ORDER = ["NO_DAY_DATA", "NO_HRV", "NO_RESTING_HR", "NO_RESPIRATION", "NO_SLEEP", "NO_STRAIN_YESTERDAY", "NO_GARMIN_CORE", "NO_SPO2", "NO_BODY_BATTERY"]


def round_half_up(x: float, places: int) -> float | int:
    """S7 storage rounding: half-up on the decimal value of the shortest repr of x, which is what Java's
    BigDecimal.valueOf(double).setScale(places, RoundingMode.HALF_UP) does."""
    q = Decimal(repr(float(x))).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP)
    return int(q) if places == 0 else float(q)


def _num(x) -> float | None:
    return None if x is None or (isinstance(x, float) and np.isnan(x)) or pd.isna(x) else float(x)


def _strain_model(c: dict) -> dict:
    """The coefficients' strain block in the shape strain.predict_model expects."""
    if c["form"] == "zones":
        return {"form": "zones", "a": c["a"], "c": c["c"], "zone_weights": c["zone_weights"]}
    return {"form": "load", "load": c["load"], "a": c["a"], "b": c["b"], "c": c["c"], "k": c["background_weight"], "background": c["uses_background"]}


def _strain_reason(row: pd.Series, has_row: bool, c: dict) -> str | None:
    """S2: why strain is blank (None when it is not)."""
    if not has_row:
        return "NO_DAY_DATA"
    if c["form"] == "zones":
        return "NO_ZONE_MINUTES" if row[list(c["zone_columns"])].isna().any() else None
    if pd.isna(row[c["load"]]):
        return "MISSING_WORKOUT_HR"
    if c["uses_background"] and pd.isna(row[strain.BACKGROUND]):
        return "NO_ALL_DAY_HR"
    return None


def score(features: pd.DataFrame, coefficients: dict) -> list[dict]:
    """One dict per grid day (S1), oldest first. Unrounded values plus the stored (rounded) ones."""
    df = features.copy()
    df["cycle_date"] = pd.to_datetime(df["cycle_date"])
    df = df.set_index("cycle_date").sort_index()
    grid = pd.date_range(df.index.min(), df.index.max(), freq="D")
    has_row = pd.Series(grid.isin(df.index), index=grid)
    d = df.reindex(grid)
    sc, sl, rc, bands = coefficients["strain"], coefficients["sleep"], coefficients["recovery"], coefficients["bands"]

    # S2 strain (blank rules from predict_model, clip inside it) and the raw value for the explanation.
    for col in [*strain.ZONE_COLUMNS, *strain.LOAD_COLUMNS, strain.BACKGROUND, "monitored_minutes"]:
        if col not in d:
            d[col] = np.nan
    model = _strain_model(sc)
    d["strain"] = strain.predict_model(model, d, sc["min_monitored_minutes"])
    if sc["form"] == "zones":
        raw = sc["c"] + sc["a"] * np.log1p(d[list(sc["zone_columns"])].fillna(0).to_numpy(dtype=float) @ np.asarray(sc["zone_weights"]))
    else:
        bg = d[strain.BACKGROUND].fillna(0) if sc["uses_background"] else 0.0
        raw = sc["c"] + sc["a"] * np.log1p(sc["b"] * (d[sc["load"]].fillna(0) + sc["background_weight"] * bg))
    d["strain_raw"] = np.where(d["strain"].notna(), raw, np.nan)
    d["low_coverage"] = d["monitored_minutes"].fillna(0) < sc["min_monitored_minutes"]

    # S3-S5
    d["prev_strain"] = d["strain"].shift(1)
    d["shortfall"] = sleep.add_shortfall(d, sl["shortfall_reference_min"], sl["shortfall_days"])
    d["sleep_need"] = sleep.need(sl, d["prev_strain"].fillna(0), d["shortfall"])
    with np.errstate(divide="ignore", invalid="ignore"):
        perf = sleep.performance(d["sleep_minutes"], d["sleep_need"])
    d["sleep_perf"] = np.where(d["sleep_need"] > 0, perf, np.nan)  # L3

    # S6 recovery
    contrib = recovery.contributions(rc, d)
    raw_rec = rc["intercept"] + contrib.sum(axis=1, skipna=False)

    out = []
    prev_reason, prev_low = "NO_DAY_DATA", False  # the day before the first grid day has no row
    for day in grid:
        row, present = d.loc[day], bool(has_row[day])
        s_reason = _strain_reason(row, present, sc)
        reasons = []
        if not present:
            reasons = [{"reason": "NO_DAY_DATA", "detail": None}]
        else:
            for t in rc["terms"]:
                if pd.isna(row[t["feature"]]):
                    reason = REASON_FOR_INPUT.get(t["feature"], "NO_" + t["feature"].upper())
                    reasons.append({"reason": reason, "detail": prev_reason if reason == "NO_STRAIN_YESTERDAY" else None})
            if not reasons and not bool(row["has_garmin_core"]):
                reasons = [{"reason": "NO_GARMIN_CORE", "detail": None}]
        reasons.sort(key=lambda r: REASON_ORDER.index(r["reason"]) if r["reason"] in REASON_ORDER else len(REASON_ORDER))
        rec_raw = None if reasons else _num(raw_rec[day])
        rec = None if rec_raw is None else float(np.clip(rec_raw, *rc["clip"]))
        st, need, sp = _num(row["strain"]), _num(row["sleep_need"]), _num(row["sleep_perf"])
        out.append(
            {
                "date": day.date().isoformat(),
                "has_row": present,
                "strain_raw": _num(row["strain_raw"]),
                "strain": st,
                "strain_reason": s_reason,
                "low_coverage": bool(row["low_coverage"]),
                "prev_strain": _num(row["prev_strain"]),
                "shortfall": _num(row["shortfall"]),
                "sleep_need": need,
                "sleep_perf": sp,
                "contributions": {t["feature"]: _num(contrib.loc[day, t["feature"]]) for t in rc["terms"]},
                "recovery_raw": rec_raw,
                "recovery": rec,
                "recovery_reasons": reasons,
                "recovery_lower_confidence": rec is not None and prev_low,  # S8
                "band": None if rec is None else metrics.band(rec, bands["green_min"], bands["red_max"]),
                "stored": {
                    "strain": None if st is None else round_half_up(st, 1),
                    "sleep_need": None if need is None else round_half_up(need, 0),
                    "sleep_perf": None if sp is None else round_half_up(sp, 0),
                    "recovery": None if rec is None else round_half_up(rec, 0),
                },
            }
        )
        prev_reason, prev_low = s_reason, bool(row["low_coverage"])
    return out
