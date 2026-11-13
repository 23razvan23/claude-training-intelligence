import numpy as np
import pandas as pd
import pytest
from synthetic import TRUE_STRAIN, make

from calibration import config, metrics, pipeline, recovery, report, strain

SETTINGS = config.load()


def test_strain_fit_recovers_known_parameters_including_background_weight():
    df = make(600)
    p = strain.fit(df["day_load_banister"], df["whoop_strain"], df["background_load"])
    assert p["a"] == pytest.approx(TRUE_STRAIN["a"], rel=0.2)
    assert p["k"] == pytest.approx(TRUE_STRAIN["k"], rel=0.3)
    pred = strain.predict(p, df["day_load_banister"], df["background_load"])
    assert np.abs(pred - df["whoop_strain"]).mean() < 0.7
    assert pred.min() >= 0 and pred.max() <= 21


def test_background_candidate_wins_when_daily_life_matters():
    m = strain.choose_and_fit(make(300))
    assert m["background"] is True
    assert m["candidates"]["day_load_banister + background"]["cv_mae"] < m["candidates"]["day_load_banister"]["cv_mae"]


def test_without_all_day_hr_strain_falls_back_to_workout_load():
    df = make(300).assign(background_load=np.nan, monitored_minutes=np.nan)
    m = strain.choose_and_fit(df)
    assert m["background"] is False and m["k"] == 0.0
    r = pipeline.run(df, SETTINGS, "f2")
    assert r.coefficients["strain"]["uses_background"] is False


def test_low_coverage_days_are_not_used_for_the_background_model():
    df = make(300)
    df.loc[df.index % 3 == 0, "monitored_minutes"] = 300  # watch off most of those days
    assert strain.background_ok(df, 600).sum() == (df.index % 3 != 0).sum()


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
    r = pipeline.run(df, SETTINGS, "f2")
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
    r = pipeline.run(df, SETTINGS, "f2", test_from=pd.Timestamp("2026-09-01").date())
    assert str(r.test_from) == "2026-09-01"
    with pytest.raises(ValueError, match="paired"):
        pipeline.run(make(300).assign(whoop_recovery=np.nan).iloc[:15], SETTINGS, "f2")


def test_report_is_self_contained_and_script_free():
    r = pipeline.run(make(300), SETTINGS, "f2")
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


def test_aligned_data_passes_the_alignment_check():
    r = pipeline.run(make(300), SETTINGS, "f2")
    assert r.metrics["strain"]["alignment"]["best"] == "same_day"
    assert not any("shifted" in n for n in r.notes)


def test_one_day_shift_is_detected_and_reported():
    df = make(300)
    # Whoop's scores dated one day early, as with the original cycle-date bug.
    for col in ("whoop_strain", "whoop_recovery", "whoop_sleep_need_min", "whoop_sleep_perf"):
        df[col] = df[col].shift(-1)
    r = pipeline.run(df, SETTINGS, "f2")
    assert r.metrics["strain"]["alignment"]["best"] == "next_day"
    assert any("shifted by one day" in n for n in r.notes)
    assert "Days look shifted" in report.render(r, "x", "m")


def test_zone_fit_recovers_monotonic_weights():
    from synthetic import make_zones

    df = make_zones(600)
    z = df[list(strain.ZONE_COLUMNS)].to_numpy()
    p = strain.fit_zones(z, df["whoop_strain"])
    w = p["zone_weights"]
    assert all(b >= a for a, b in zip(w, w[1:], strict=False))  # never decreasing with intensity
    assert all(x >= 0 for x in w)
    assert np.abs(strain.predict_zones(p, z) - df["whoop_strain"]).mean() < 0.6


def test_zone_model_wins_when_workouts_go_unrecorded():
    from synthetic import make_zones

    m = strain.choose_and_fit(make_zones(300))
    assert m["form"] == "zones"
    assert m["candidates"][strain.ZONES_NAME]["cv_mae"] < min(v["cv_mae"] for k, v in m["candidates"].items() if k != strain.ZONES_NAME)


def test_pipeline_with_zones_scores_low_coverage_days_and_reports_misses():
    from synthetic import make_zones

    df = make_zones(300)
    df.loc[df.index[-20:], "monitored_minutes"] = 300  # watch mostly off on 20 test days
    r = pipeline.run(df, SETTINGS, "f3")
    assert r.coefficients["strain"]["form"] == "zones"
    assert len(r.coefficients["strain"]["zone_weights"]) == 6
    assert r.metrics["strain"]["low_coverage"]["n"] == 20  # scored and reported separately, not dropped
    assert r.metrics["strain"]["full_coverage"]["n"] > 0
    html = report.render(r, "x", "m")
    assert "Weight per minute" in html and "Largest strain misses" in html and "lower confidence" in html
    assert "Strain rank correlation" in html and "Selected:" in html


def test_without_zone_minutes_the_load_form_is_used():
    r = pipeline.run(make(300), SETTINGS, "f3")
    assert r.coefficients["strain"]["form"] == "load"


def test_parsimony_rule_needs_a_clear_gain_for_the_complex_form():
    cands = {
        "edwards + background": {"cv_mae": 1.86},
        "banister + background": {"cv_mae": 1.90},
        strain.ZONES_NAME: {"cv_mae": 1.77},  # 4.8% better: not enough
    }
    name, why = strain.select(cands, ["edwards + background", "banister + background"], [strain.ZONES_NAME], 0.10)
    assert name == "edwards + background" and "only 5% better" in why
    cands[strain.ZONES_NAME]["cv_mae"] = 1.60  # 14% better: switch
    name, _ = strain.select(cands, ["edwards + background", "banister + background"], [strain.ZONES_NAME], 0.10)
    assert name == strain.ZONES_NAME


def test_background_is_part_of_the_base_form_when_all_day_hr_exists():
    m = strain.choose_and_fit(make(300))
    assert m["form"] == "load" and m["background"] is True  # spec: workout load + daily HR load
    assert "day_load_banister" in m["candidates"]  # workout-only fits are still reported


def test_acceptance_uses_full_coverage_days_and_checks_rank_correlation():
    df = make(300)
    df.loc[df.index[-15:], "monitored_minutes"] = 200
    r = pipeline.run(df, SETTINGS, "f3")
    assert r.metrics["strain"]["n"] == r.metrics["strain"]["full_coverage"]["n"]
    assert r.metrics["strain"]["low_coverage"]["n"] == 15
    assert "strain_spearman" in r.metrics["checks"]
