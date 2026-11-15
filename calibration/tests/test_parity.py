"""The Python side of the Java/Python parity test (R-03, ADR 0008).

backend/src/test/resources/parity/<case>/ holds day_features.csv (inputs at database precision) and
expected.json (coefficients plus every per-day value the daily scoring must reproduce). These tests
fail when the committed files are missing or stale; regenerate with
    cd calibration && python tests/parity_fixtures.py --write

Staleness is checked in two parts, because the zones fit (8 parameters) lands a few 1e-6 apart on
different machines (seen between a dev container and CI):
  strict  scoring the committed CSV with the committed coefficients reproduces the committed days
          (no fitting involved, so this is machine-independent): catches any change to the scoring rules;
  loose   a fresh fit stays close to the committed coefficients and inputs: catches changes to the fitting
          code or the synthetic tables.
The other tests read the committed files, which are exactly what Java reads.
"""

import json
import math
import re
from pathlib import Path

import jsonschema
import pytest
from parity_fixtures import CASES, COLUMNS, FIXTURE_DIR, build, from_csv

from calibration import __main__ as cli
from calibration import parity

REPO = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((REPO / "calibration" / "contract.schema.json").read_text(encoding="utf-8"))
STRICT = 1e-9  # relative, same coefficients and inputs: only libm rounding may differ
LOOSE = 1e-3  # relative, a fresh fit on another machine: optimizer end points differ slightly


def close(a, b, path="", rel=STRICT) -> None:
    """Recursive equality; floats within rel."""
    if isinstance(a, float) or isinstance(b, float):
        assert a is not None and b is not None, path
        assert math.isclose(a, b, rel_tol=rel, abs_tol=rel), f"{path}: {a} != {b}"
    elif isinstance(a, dict):
        assert isinstance(b, dict) and set(a) == set(b), f"{path}: keys {sorted(a)} != {sorted(b)}"
        for k in a:
            close(a[k], b[k], f"{path}.{k}", rel)
    elif isinstance(a, list):
        assert isinstance(b, list) and len(a) == len(b), f"{path}: length"
        for i, (x, y) in enumerate(zip(a, b, strict=True)):
            close(x, y, f"{path}[{i}]", rel)
    else:
        assert a == b, f"{path}: {a!r} != {b!r}"


def committed(case: str) -> tuple[str, dict]:
    folder = FIXTURE_DIR / case
    assert (folder / "day_features.csv").exists(), f"missing fixture {folder}: run python tests/parity_fixtures.py --write"
    return (folder / "day_features.csv").read_text(encoding="utf-8"), json.loads((folder / "expected.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def fresh():
    return {case: build(case) for case in CASES}


@pytest.mark.parametrize("case", CASES)
def test_committed_days_follow_from_the_committed_inputs_and_coefficients(case):
    csv_text, expected = committed(case)
    days = json.loads(json.dumps(parity.score(from_csv(csv_text), expected["coefficients"])))
    close(expected["days"], days, "days")


@pytest.mark.parametrize("case", CASES)
def test_committed_fixture_matches_a_fresh_generation(case, fresh):
    csv_text, expected = committed(case)
    fresh_csv, fresh_expected = fresh[case]
    fresh_expected = json.loads(json.dumps(fresh_expected))
    close(expected["coefficients"], fresh_expected["coefficients"], "coefficients", LOOSE)
    assert expected["planted"] == fresh_expected["planted"]
    a, b = from_csv(csv_text), from_csv(fresh_csv)
    assert list(a["cycle_date"]) == list(b["cycle_date"]) and list(a["has_garmin_core"]) == list(b["has_garmin_core"])
    for col, scale in COLUMNS.items():
        if isinstance(scale, int):
            # one unit of the stored precision: a band-edge input is solved from the fitted coefficients
            assert a[col].isna().equals(b[col].isna()), col
            assert ((a[col] - b[col]).abs().fillna(0) <= 1.01 * 10.0**-scale).all(), col


@pytest.mark.parametrize("case", CASES)
def test_fixture_files_are_small(case):
    for name in ("day_features.csv", "expected.json"):
        assert (FIXTURE_DIR / case / name).stat().st_size < 200_000


@pytest.mark.parametrize("case", ["load", "zones"])
def test_fitted_fixtures_carry_a_valid_contract(case):
    c = committed(case)[1]["coefficients"]
    jsonschema.validate(json.loads(json.dumps(c)), SCHEMA)
    assert c["strain"]["form"] == case


def _days(expected) -> dict:
    return {d["date"]: d for d in expected["days"]}


@pytest.mark.parametrize("case", ["load", "zones"])
def test_every_planted_case_is_present(case):
    expected = committed(case)[1]
    days, planted = _days(expected), expected["planted"]
    day = lambda name: days[planted[name]]  # noqa: E731

    assert day("missing_day")["has_row"] is False and day("missing_day")["strain_reason"] == "NO_DAY_DATA"
    assert [r["reason"] for r in day("missing_day")["recovery_reasons"]] == ["NO_DAY_DATA"]
    if case == "load":
        assert day("no_all_day_hr")["strain_reason"] == "NO_ALL_DAY_HR"
        assert day("missing_workout_hr")["strain_reason"] == "MISSING_WORKOUT_HR"
        nsy = day("no_strain_yesterday")
        assert nsy["recovery"] is None and {"reason": "NO_STRAIN_YESTERDAY", "detail": "MISSING_WORKOUT_HR"} in nsy["recovery_reasons"]
    else:
        assert day("missing_zone_minutes")["strain_reason"] == "NO_ZONE_MINUTES"
        assert day("no_all_day_hr")["strain"] is not None  # zones form does not read background_load
    assert day("low_coverage")["low_coverage"] is True and day("low_coverage")["strain"] is not None
    lcy = day("low_coverage_yesterday")
    assert lcy["recovery"] is not None and lcy["recovery_lower_confidence"] is True
    assert day("no_sleep")["sleep_perf"] is None and "NO_SLEEP" in [r["reason"] for r in day("no_sleep")["recovery_reasons"]]
    assert "NO_HRV" in [r["reason"] for r in day("no_hrv")["recovery_reasons"]]
    zero = day("zero_sleep")
    assert zero["sleep_perf"] == 0 and [r["reason"] for r in zero["recovery_reasons"]] == ["NO_GARMIN_CORE"]
    assert day("strain_above_21")["strain"] == 21 and day("strain_above_21")["strain_raw"] > 21
    hi, lo = day("recovery_above_100"), day("recovery_below_0")
    assert hi["recovery_raw"] > 100 and hi["recovery"] == 100 and hi["band"] == "GREEN"
    assert lo["recovery_raw"] < 0 and lo["recovery"] == 0 and lo["band"] == "RED"
    # Band edges: the band comes from the unrounded value (S7), so a stored 67 can be yellow.
    edges = {name: day(name) for name in ("band_edge_66_7", "band_edge_67_2", "band_edge_33_3", "band_edge_32_8")}
    assert [(d["stored"]["recovery"], d["band"]) for d in edges.values()] == [(67, "YELLOW"), (67, "GREEN"), (33, "YELLOW"), (33, "RED")]
    for d in edges.values():
        assert abs(d["recovery"] - round(d["recovery"])) < 0.5 and min(abs(d["recovery"] - e) for e in (33, 34, 66, 67)) < 0.5


# ADR 0008's results table (unrounded; None = blank), computed there with the real Python functions.
ADR_TABLE = {
    "2026-03-01": (7.1481, None, 0, 400.0000, 100.0000, None, None),
    "2026-03-02": (5.7097, 7.1481, 0, 421.4443, 94.9117, 59.1289, "YELLOW"),
    "2026-03-03": (9.5866, 5.7097, 50, 419.6291, 90.5562, 48.9540, "YELLOW"),
    "2026-03-04": (None, 9.5866, 120, 434.7598, 100.0000, 60.3434, "YELLOW"),
    "2026-03-05": (None, None, 120, 406.0000, None, None, None),
    "2026-03-06": (5.8040, None, 120, 406.0000, None, None, None),
    "2026-03-07": (5.2616, 5.8040, 120, 423.4121, 100.0000, 63.3260, "YELLOW"),
    "2026-03-08": (None, 5.2616, 120, 421.7849, 100.0000, 72.8684, "GREEN"),
    "2026-03-09": (6.1209, None, 120, 406.0000, 100.0000, None, None),
    "2026-03-10": (21.0000, 6.1209, 100, 423.3626, 100.0000, 64.6091, "YELLOW"),
}
ADR_REASONS = {
    "2026-03-01": {"NO_STRAIN_YESTERDAY"},
    "2026-03-05": {"NO_DAY_DATA"},
    "2026-03-06": {"NO_STRAIN_YESTERDAY", "NO_SLEEP"},
    "2026-03-09": {"NO_STRAIN_YESTERDAY"},
}


def test_adr_0008_worked_example_is_reproduced():
    days = _days(committed("adr0008")[1])
    assert list(days) == list(ADR_TABLE)
    for date, (st, prev, short, need, perf, rec, band) in ADR_TABLE.items():
        d = days[date]
        got = (d["strain"], d["prev_strain"], d["shortfall"], d["sleep_need"], d["sleep_perf"], d["recovery"], d["band"])
        names = ("strain", "prev_strain", "shortfall", "need", "perf", "recovery", "band")
        for name, want, have in zip(names, (st, prev, short, need, perf, rec, band), got, strict=True):
            if want is None or isinstance(want, str):
                assert have == want, (date, name, have)
            else:
                assert have == pytest.approx(want, abs=5e-5), (date, name, have)
        assert {r["reason"] for r in d["recovery_reasons"]} == ADR_REASONS.get(date, set()), date
    assert days["2026-03-06"]["low_coverage"] is True and days["2026-03-07"]["recovery_lower_confidence"] is True
    assert days["2026-03-04"]["strain_reason"] == "NO_ALL_DAY_HR" and days["2026-03-08"]["strain_reason"] == "MISSING_WORKOUT_HR"
    assert days["2026-03-02"]["stored"] == {"strain": 5.7, "sleep_need": 421, "sleep_perf": 95, "recovery": 59}
    assert days["2026-03-02"]["contributions"]["prev_strain"] == pytest.approx(2.1819, abs=5e-5)


def test_round_half_up_matches_the_storage_rule():
    from calibration import parity

    assert parity.round_half_up(66.5, 0) == 67 and parity.round_half_up(33.5, 0) == 34 and parity.round_half_up(9.85, 1) == 9.9
    assert parity.round_half_up(2.675, 2) == 2.68  # decimal value of the shortest repr, as Java BigDecimal.valueOf


def test_feature_version_matches_the_backend():
    yml = (REPO / "backend" / "src" / "main" / "resources" / "application.yml").read_text(encoding="utf-8")
    found = re.findall(r"^\s*feature-version:\s*([\w.-]+)", yml, flags=re.MULTILINE)
    assert found == [cli.FEATURE_VERSION], f"application.yml app.metrics.feature-version {found} != calibration FEATURE_VERSION {cli.FEATURE_VERSION}"
