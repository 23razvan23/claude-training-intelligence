import numpy as np
import pandas as pd
import pytest
from synthetic import TRUE_STRAIN, make

from calibration import config, metrics, pipeline, recovery, report, strain

SETTINGS = config.load()


def test_strain_fit_recovers_known_parameters():
    df = make(600)
    p = strain.fit(df["day_load_banister"], df["whoop_strain"])
    assert p["a"] == pytest.approx(TRUE_STRAIN["a"], rel=0.15)
    assert p["c"] == pytest.approx(TRUE_STRAIN["c"], abs=0.5)
    pred = strain.predict(p, df["day_load_banister"])
    assert np.abs(pred - df["whoop_strain"]).mean() < 0.7
    assert pred.min() >= 0 and pred.max() <= 21


def test_band_metrics_on_a_hand_made_case():
    whoop = np.array([80, 70, 50, 20, 10])
    ours = np.array([75, 30, 55, 25, 70])  # same, red-vs-green, same, same, green-vs-red
    r = metrics.band_agreement(whoop, ours, 67, 33)
    assert r["agreement"] == pytest.approx(3 / 5)
    assert r["green_red_rate"] == pytest.approx(2 / 5)
    assert r["confusion"]["GREEN"]["RED"] == 1 and r["confusion"]["RED"]["GREEN"] == 1
    assert metrics.band(67, 67, 33) == "GREEN" and metrics.band(33, 67, 33) == "RED" and metrics.band(34, 67, 33) == "YELLOW"


def test_summary_bland_altman():
    s = metrics.summarize(np.array([1.0, 2, 3, 4]), np.array([2.0, 3, 4, 5]))
    assert s["mae"] == 1 and s["bias"] == 1 and s["loa_low"] == pytest.approx(1) and s["spearman"] == pytest.approx(1)


def test_recovery_weights_are_non_negative_and_monotonic():
    df = make(300).dropna(subset=["ln_rmssd_z"]).copy()
    df["sleep_perf"] = np.clip(100 * df["sleep_minutes"] / 470, 0, 100)
    df["prev_strain"] = df["whoop_strain"].shift(1)
    df = df.dropna(subset=["prev_strain"])
    model = recovery.fit(df, recovery.CORE, alpha=1.0)
    assert all(t["weight"] >= 0 for t in model["terms"])
    base = df.iloc[[0]].copy()
    better_hrv = base.assign(ln_rmssd_z=base["ln_rmssd_z"] + 2)
    higher_rhr = base.assign(rhr_dev=base["rhr_dev"] + 5)
    assert recovery.predict(model, better_hrv)[0] >= recovery.predict(model, base)[0]
    assert recovery.predict(model, higher_rhr)[0] <= recovery.predict(model, base)[0]
    # HRV is the strongest true driver
    weights = {t["feature"]: t["weight"] for t in model["terms"]}
    assert weights["ln_rmssd_z"] == max(weights.values())


def test_pipeline_split_is_chronological_and_targets_are_met_on_clean_data():
    df = make(300)
    r = pipeline.run(df, SETTINGS, "f1")
    assert r.train_to < r.test_from
    assert r.test_days.index.min() >= pd.Timestamp(r.test_from)
    paired = df.dropna(subset=["whoop_recovery"])
    assert r.test_from == paired["cycle_date"].iloc[int(len(paired) * 0.8)]
    assert r.metrics["strain"]["mae"] <= SETTINGS.strain_mae_max
    assert r.metrics["recovery"]["agreement"] > 0.6
    assert set(r.coefficients) == {"feature_version", "strain", "sleep", "recovery", "bands"}
    assert r.exclusions["no_baseline_yet"] == 14


def test_explicit_test_from_and_too_little_data():
    df = make(300)
    r = pipeline.run(df, SETTINGS, "f1", test_from=pd.Timestamp("2026-09-01").date())
    assert str(r.test_from) == "2026-09-01"
    with pytest.raises(ValueError, match="paired"):
        pipeline.run(make(300).assign(whoop_recovery=np.nan).iloc[:15], SETTINGS, "f1")


def test_report_is_self_contained_and_script_free():
    r = pipeline.run(make(300), SETTINGS, "f1")
    html = report.render(r, "Test <athlete>", "m1-20261109")
    assert html.startswith("<!doctype html>")
    assert "<script" not in html.lower()
    assert "Strain MAE" in html and "Recovery band agreement" in html
    assert "Test &lt;athlete&gt;" in html  # names are escaped
    assert "data:image/png;base64," in html
    assert "http://" not in html and "https://" not in html  # no remote loads


def test_nan_is_stored_as_null_json():
    from calibration import data

    assert data._clean({"a": float("nan"), "b": [1.0, float("nan")], "c": {"d": 2}}) == {"a": None, "b": [1.0, None], "c": {"d": 2}}
