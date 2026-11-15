"""Builds the Java/Python parity fixtures (R-03). Synthetic data only.

    cd calibration && python tests/parity_fixtures.py --write

writes backend/src/test/resources/parity/<case>/day_features.csv and expected.json for
  adr0008  the worked example of docs/adr/0008-scoring-contract.md, with its hand-set coefficients;
  load     a synthetic table fitted by pipeline.run (load form), then a 60-day window with planted edge cases;
  zones    the same for the whole-day zones form.
The CSV is what Java reads (day_features columns at database precision); expected.json is what it must
produce, computed by calibration.parity.score from that same CSV.
"""

from __future__ import annotations

import io
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from synthetic import make, make_zones  # noqa: E402

from calibration import config, data, parity, pipeline  # noqa: E402
from calibration.__main__ import FEATURE_VERSION  # noqa: E402

FIXTURE_DIR = Path(__file__).resolve().parents[2] / "backend" / "src" / "test" / "resources" / "parity"
CASES = ["adr0008", "load", "zones"]

# day_features columns the scorer reads, with their database scale (V6, V8, V10): "date", "bool", or decimals.
COLUMNS = {
    "cycle_date": "date",
    "has_garmin_core": "bool",
    "ln_rmssd_z": 3,
    "rhr_dev": 2,
    "resp_dev": 2,
    "sleep_minutes": 0,
    "sleep_spo2": 1,
    "body_battery_high": 0,
    "day_load_banister": 2,
    "day_load_edwards": 2,
    "background_load": 2,
    "monitored_minutes": 0,
    **{f"zone_min_{z}": 1 for z in range(6)},
}
WINDOW_DAYS = 60
FEATURE_CONFIG = {"hr_max": 190, "hr_rest": 50, "sex_assumed": False}  # made-up profile

# Window offset of each planted case (fitted fixtures).
PLANTS = {
    "missing_day": 10,
    "no_all_day_hr": 15,
    "missing_zone_minutes": 17,
    "missing_workout_hr": 20,
    "no_strain_yesterday": 21,
    "low_coverage": 25,
    "low_coverage_yesterday": 26,
    "no_sleep": 30,
    "no_hrv": 35,
    "zero_sleep": 38,
    "strain_above_21": 40,
    "recovery_above_100": 44,
    "recovery_below_0": 47,
    "band_edge_66_7": 50,
    "band_edge_67_2": 52,
    "band_edge_33_3": 54,
    "band_edge_32_8": 56,
}
RECOVERY_TARGETS = {
    "recovery_above_100": 115.0,
    "recovery_below_0": -15.0,
    "band_edge_66_7": 66.7,  # stored 67, still YELLOW
    "band_edge_67_2": 67.2,
    "band_edge_33_3": 33.3,  # stored 33, still YELLOW
    "band_edge_32_8": 32.8,
}


def to_csv(df: pd.DataFrame) -> str:
    """day_features rows as Java will read them: database precision, empty = NULL, true/false."""
    lines = [",".join(COLUMNS)]
    for _, row in df.iterrows():
        cells = []
        for col, scale in COLUMNS.items():
            v = row.get(col, np.nan)
            if scale == "date":
                cells.append(pd.Timestamp(v).date().isoformat())
            elif scale == "bool":
                cells.append("true" if bool(v) else "false")
            elif v is None or pd.isna(v):
                cells.append("")
            elif scale == 0:
                cells.append(str(parity.round_half_up(v, 0)))
            else:
                cells.append(f"{parity.round_half_up(v, scale):.{scale}f}")
        lines.append(",".join(cells))
    return "\n".join(lines) + "\n"


def from_csv(text: str) -> pd.DataFrame:
    dtypes = {c: float for c, s in COLUMNS.items() if isinstance(s, int)} | {"has_garmin_core": str, "cycle_date": str}
    df = pd.read_csv(io.StringIO(text), dtype=dtypes, keep_default_na=False, na_values=[""])
    df["has_garmin_core"] = df["has_garmin_core"].map({"true": True, "false": False})
    df["cycle_date"] = pd.to_datetime(df["cycle_date"]).dt.date
    return df


# ---------------------------------------------------------------- adr0008

ADR_COEFFICIENTS = {
    "strain": {
        "form": "load",
        "load": "day_load_edwards",
        "a": 5.0,
        "b": 0.01,
        "c": 2.0,
        "background_weight": 0.2,
        "uses_background": True,
        "min_monitored_minutes": 600,
        "clip": [0, 21],
    },
    "sleep": {"base_min": 400.0, "per_strain_min": 3.0, "per_shortfall": 0.05, "shortfall_reference_min": 450.0, "shortfall_days": 7, "performance_clip": [0, 100]},
    "recovery": {
        "intercept": 55.0,
        "terms": [
            {"feature": "ln_rmssd_z", "sign": 1, "weight": 8.0, "center": 0.1, "scale": 1.0},
            {"feature": "rhr_dev", "sign": -1, "weight": 5.0, "center": 0.2, "scale": 2.5},
            {"feature": "resp_dev", "sign": -1, "weight": 2.0, "center": 0.0, "scale": 0.5},
            {"feature": "sleep_perf", "sign": 1, "weight": 6.0, "center": 90.0, "scale": 10.0},
            {"feature": "prev_strain", "sign": -1, "weight": 3.0, "center": -9.33, "scale": 3.0},
        ],
        "clip": [0, 100],
    },
    "bands": {"green_min": 67, "red_max": 33},
}
N = None
# date, edwards, background, monitored, sleep, ln_rmssd_z, rhr_dev, resp_dev, has_garmin_core (03-05 has no row)
ADR_ROWS = [
    ("2026-03-01", 120, 300, 900, 480, 0.5, -1.0, -0.2, True),
    ("2026-03-02", 60, 250, 840, 400, 0.2, 0.5, 0.1, True),
    ("2026-03-03", 300, 280, 880, 380, -0.4, 2.0, 0.4, True),
    ("2026-03-04", 0, N, N, 460, 0.1, 0.0, 0.0, True),
    ("2026-03-06", 90, 120, 300, N, 0.3, -0.5, 0.0, False),
    ("2026-03-07", 40, 260, 860, 450, 0.0, 0.0, 0.0, True),
    ("2026-03-08", N, 270, 870, 470, 0.6, -1.5, -0.3, True),
    ("2026-03-09", 80, 240, 850, 420, 0.4, -0.5, -0.1, True),
    ("2026-03-10", 5000, 2000, 900, 500, 0.2, 0.0, 0.0, True),
]


def _adr_table() -> pd.DataFrame:
    cols = ["cycle_date", "day_load_edwards", "background_load", "monitored_minutes", "sleep_minutes", "ln_rmssd_z", "rhr_dev", "resp_dev", "has_garmin_core"]
    return pd.DataFrame(ADR_ROWS, columns=cols).astype({c: float for c in cols[1:-1]})


# ---------------------------------------------------------------- fitted fixtures


def _plant(window: pd.DataFrame, form: str) -> tuple[pd.DataFrame, dict]:
    w = window.reset_index(drop=True).copy()
    planted = {name: pd.Timestamp(w.loc[i, "cycle_date"]).date().isoformat() for name, i in PLANTS.items()}
    zones = [f"zone_min_{z}" for z in range(6)]
    w.loc[PLANTS["no_all_day_hr"], ["background_load", "monitored_minutes"]] = np.nan
    w.loc[PLANTS["missing_zone_minutes"], zones] = np.nan
    w.loc[PLANTS["missing_workout_hr"], ["day_load_banister", "day_load_edwards"]] = np.nan
    w.loc[PLANTS["low_coverage"], "monitored_minutes"] = 300
    w.loc[PLANTS["no_sleep"], "sleep_minutes"] = np.nan
    w.loc[PLANTS["no_sleep"], "has_garmin_core"] = False
    w.loc[PLANTS["no_hrv"], "ln_rmssd_z"] = np.nan
    w.loc[PLANTS["no_hrv"], "has_garmin_core"] = False
    w.loc[PLANTS["zero_sleep"], "sleep_minutes"] = 0
    w.loc[PLANTS["zero_sleep"], "has_garmin_core"] = False
    w.loc[PLANTS["strain_above_21"], ["day_load_banister", "day_load_edwards", "background_load"]] = [5000, 5000, 2000]
    if form == "zones":
        w.loc[PLANTS["strain_above_21"], ["zone_min_4", "zone_min_5"]] = [600, 600]
    w = w.drop(index=PLANTS["missing_day"])
    return w, planted


def _hit_recovery_targets(window: pd.DataFrame, coefficients: dict, planted: dict) -> pd.DataFrame:
    """Sets ln_rmssd_z on the chosen days so raw recovery lands on its target. ln_rmssd_z only enters
    that day's recovery, so one pass is enough; it is rounded to its database scale (3 decimals)."""
    term = next(t for t in coefficients["recovery"]["terms"] if t["feature"] == "ln_rmssd_z")
    assert term["weight"] > 0, "synthetic fit should give HRV a positive weight"
    days = {d["date"]: d for d in parity.score(from_csv(to_csv(window)), coefficients)}
    w = window.copy()
    dates = w["cycle_date"].map(lambda v: pd.Timestamp(v).date().isoformat())
    for name, target in RECOVERY_TARGETS.items():
        d = days[planted[name]]
        assert d["recovery_raw"] is not None, name
        rest = d["recovery_raw"] - d["contributions"]["ln_rmssd_z"]
        w.loc[dates == planted[name], "ln_rmssd_z"] = term["center"] + term["scale"] * (target - rest) / term["weight"]
    return w


def _fitted(form: str) -> tuple[pd.DataFrame, dict, dict]:
    base = make(300) if form == "load" else make_zones(300)
    inputs = from_csv(to_csv(base))  # database precision, also for the fit
    table = base.drop(columns=[c for c in COLUMNS if c in base and c != "cycle_date"]).join(inputs.drop(columns=["cycle_date"]))
    start = WINDOW_DAYS
    test_from = table["cycle_date"].iloc[-start]
    result = pipeline.run(table, config.load(), FEATURE_VERSION, test_from=test_from, context={"feature_config": FEATURE_CONFIG})
    coefficients = json.loads(json.dumps(data._clean(result.coefficients), default=data._json_default))
    assert coefficients["strain"]["form"] == form
    window, planted = _plant(table.iloc[-start:][list(COLUMNS)], form)
    window = _hit_recovery_targets(window, coefficients, planted)
    return window, coefficients, planted


def build(case: str) -> tuple[str, dict]:
    """(day_features.csv text, expected.json content) for one case."""
    if case == "adr0008":
        rows, coefficients, planted = _adr_table(), ADR_COEFFICIENTS, {}
        description = "Worked example of ADR 0008 with its hand-set coefficients."
    else:
        rows, coefficients, planted = _fitted(case)
        description = f"Synthetic table fitted by pipeline.run ({case} form); last {WINDOW_DAYS} days with planted edge cases."
    csv_text = to_csv(rows)
    expected = {
        "case": case,
        "description": description,
        "generated_by": "calibration/tests/parity_fixtures.py",
        "coefficients": coefficients,
        "planted": planted,
        "days": parity.score(from_csv(csv_text), coefficients),
    }
    return csv_text, expected


def write() -> None:
    for case in CASES:
        csv_text, expected = build(case)
        folder = FIXTURE_DIR / case
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "day_features.csv").write_text(csv_text, encoding="utf-8")
        (folder / "expected.json").write_text(json.dumps(expected, indent=1) + "\n", encoding="utf-8")
        print(f"wrote {folder}")


if __name__ == "__main__":
    if sys.argv[1:] != ["--write"]:
        raise SystemExit(__doc__)
    write()
