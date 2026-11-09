"""Targets and settings, loaded from targets.json (all [configurable])."""

from __future__ import annotations

import json
from dataclasses import dataclass
from importlib import resources


@dataclass(frozen=True)
class Settings:
    strain_mae_max: float
    recovery_band_agreement_min: float
    recovery_green_red_max: float
    green_min: int
    red_max: int
    test_share: float
    recovery_ridge_alpha: float
    optional_feature_min_coverage: float
    optional_feature_min_gain: float
    challenger_mae_gain: float
    challenger_band_gain: float
    sleep_shortfall_days: int


def load(path: str | None = None) -> Settings:
    if path:
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
    else:
        raw = json.loads(resources.files("calibration").joinpath("targets.json").read_text(encoding="utf-8"))
    return Settings(
        strain_mae_max=raw["strain_mae_max"],
        recovery_band_agreement_min=raw["recovery_band_agreement_min"],
        recovery_green_red_max=raw["recovery_green_red_max"],
        green_min=raw["bands"]["green_min"],
        red_max=raw["bands"]["red_max"],
        test_share=raw["test_share"],
        recovery_ridge_alpha=raw["recovery_ridge_alpha"],
        optional_feature_min_coverage=raw["optional_feature_min_coverage"],
        optional_feature_min_gain=raw["optional_feature_min_gain"],
        challenger_mae_gain=raw["challenger_mae_gain"],
        challenger_band_gain=raw["challenger_band_gain"],
        sleep_shortfall_days=raw["sleep_shortfall_days"],
    )
