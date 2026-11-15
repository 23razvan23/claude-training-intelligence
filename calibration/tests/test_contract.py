"""The coefficients contract read by daily scoring (R-02, ADR 0008): shape, ranges, profile snapshot."""

import json
from datetime import date
from pathlib import Path

import jsonschema
import pytest
from synthetic import make, make_zones

from calibration import config, data, pipeline

SETTINGS = config.load()
SCHEMA = json.loads((Path(__file__).parent.parent / "contract.schema.json").read_text(encoding="utf-8"))
CONTEXT = {"feature_config": {"hr_max": 184, "hr_rest": 48, "sex_assumed": False}}


def stored(result) -> dict:
    """The coefficients exactly as save_model writes them to the database."""
    return json.loads(json.dumps(data._clean(result.coefficients), default=data._json_default))


def strain_inputs(c: dict) -> list[str]:
    s = c["strain"]
    if s["form"] == "zones":
        return list(s["zone_columns"])
    return [s["load"], "background_load"] if s["uses_background"] else [s["load"]]


@pytest.fixture(scope="module")
def load_run():
    return pipeline.run(make(300), SETTINGS, "f3", context=CONTEXT)


@pytest.fixture(scope="module")
def zones_run():
    return pipeline.run(make_zones(300), SETTINGS, "f3", context=CONTEXT)


def test_schema_itself_is_valid():
    jsonschema.Draft202012Validator.check_schema(SCHEMA)


@pytest.mark.parametrize("which", ["load_run", "zones_run"])
def test_both_strain_forms_produce_a_valid_contract(which, request):
    c = stored(request.getfixturevalue(which))
    jsonschema.validate(c, SCHEMA)
    assert c["schema_version"] == 2
    assert c["strain"]["form"] == ("zones" if which == "zones_run" else "load")
    assert c["strain"]["clip"] == [0, 21] and c["sleep"]["performance_clip"] == [0, 100]


def test_contract_without_context_is_valid_with_unknown_profile():
    c = stored(pipeline.run(make(300), SETTINGS, "f3"))
    jsonschema.validate(c, SCHEMA)
    assert c["feature_config"] == {"hr_max": None, "hr_rest": None, "sex_assumed": None}


def test_schema_rejects_a_contract_without_a_version_or_with_unknown_keys(load_run):
    c = stored(load_run)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({k: v for k, v in c.items() if k != "schema_version"}, SCHEMA)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({**c, "surprise": 1}, SCHEMA)


def test_feature_config_and_calibration_version_are_recorded(load_run):
    from calibration import __version__

    assert load_run.coefficients["feature_config"] == CONTEXT["feature_config"]
    assert load_run.coefficients["calibration_version"] == __version__


@pytest.mark.parametrize("which", ["load_run", "zones_run"])
def test_every_model_input_has_an_ordered_training_range(which, request):
    c = request.getfixturevalue(which).coefficients
    ranges = c["training_ranges"]
    expected = {
        "strain": strain_inputs(c),
        "sleep": ["prev_strain", "shortfall"],
        "recovery": [t["feature"] for t in c["recovery"]["terms"]],
    }
    assert {"prev_strain", "sleep_perf"} <= set(expected["recovery"])
    for part, inputs in expected.items():
        assert sorted(ranges[part]) == sorted(inputs), part
        for name in inputs:
            r = ranges[part][name]
            assert r["min"] <= r["p01"] <= r["p99"] <= r["max"], (part, name, r)
            assert r["n"] > 0


def test_training_ranges_use_only_the_rows_the_model_was_fitted_on():
    df = make(300)
    split = date(2026, 9, 1)
    huge = df["cycle_date"] >= split
    df.loc[huge, "background_load"] = 1e6  # only in the test window
    c = pipeline.run(df, SETTINGS, "f3", test_from=split).coefficients
    assert c["training_ranges"]["strain"]["background_load"]["max"] < 1e6
    n_recovery = c["training_ranges"]["recovery"]["prev_strain"]["n"]
    assert all(r["n"] == n_recovery for r in c["training_ranges"]["recovery"].values())


def test_test_to_limits_the_test_window_and_keeps_the_coefficients():
    df = make(300)
    test_from, test_to = date(2026, 9, 1), date(2026, 9, 30)
    full = pipeline.run(df, SETTINGS, "f3", test_from=test_from)
    cut = pipeline.run(df, SETTINGS, "f3", test_from=test_from, test_to=test_to)
    assert cut.test_to <= test_to < full.test_to
    assert cut.test_days.index.max().date() <= test_to
    assert cut.strain_days.index.max().date() <= test_to
    assert cut.metrics["recovery"]["n"] < full.metrics["recovery"]["n"]
    assert cut.coefficients == full.coefficients


def test_test_to_before_test_from_is_refused():
    with pytest.raises(ValueError, match="test-to"):
        pipeline.run(make(300), SETTINGS, "f3", test_from=date(2026, 9, 1), test_to=date(2026, 8, 1))


class _FakeCursor:
    def __init__(self, row):
        self.row, self.sql = row, None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params):
        self.sql, self.params = sql, params

    def fetchone(self):
        return self.row


class _FakeConn:
    def __init__(self, row):
        self.cur = _FakeCursor(row)

    def cursor(self):
        return self.cur


def test_feature_config_reads_the_newest_activity_load_row_or_reports_unknown():
    conn = _FakeConn((184, 48, False))
    assert data.feature_config(conn, 1, "f3") == {"hr_max": 184, "hr_rest": 48, "sex_assumed": False}
    assert "activity_load" in conn.cur.sql and "ORDER BY computed_at DESC" in conn.cur.sql and conn.cur.params == (1, "f3")
    assert data.feature_config(_FakeConn(None), 1, "f3") == {"hr_max": None, "hr_rest": None, "sex_assumed": None}


def test_command_line_passes_test_to_and_the_profile_into_the_stored_contract(monkeypatch, capsys):
    from contextlib import nullcontext

    from calibration import __main__ as cli

    saved = {}
    monkeypatch.setattr(data, "connect", lambda: nullcontext(object()))
    monkeypatch.setattr(data, "athlete_name", lambda conn, a: "Synthetic")
    monkeypatch.setattr(data, "load_features", lambda conn, a, fv: make(300))
    monkeypatch.setattr(data, "feature_config", lambda conn, a, fv: dict(CONTEXT["feature_config"]))
    monkeypatch.setattr(data, "next_version", lambda conn, a, today: "m1-test")
    monkeypatch.setattr(data, "save_model", lambda conn, a, version, result, html: saved.setdefault("r", result) and 1)

    assert cli.main(["--athlete", "1", "--test-from", "2026-09-01", "--test-to", "2026-09-30"]) == 0
    r = saved["r"]
    assert r.test_to <= date(2026, 9, 30)
    jsonschema.validate(stored(r), SCHEMA)
    assert r.coefficients["feature_config"] == CONTEXT["feature_config"]
    assert "Contract schema 2" in capsys.readouterr().out
