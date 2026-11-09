"""Calibrate Module 1 against Whoop for one athlete.

    python -m calibration --athlete 1 [--test-from 2026-09-01] [--out report.html] [--dry-run]

Reads day_features (run "Recompute features" in the app first), fits strain, sleep and
recovery on the training window, validates on the held-out window, and stores a CANDIDATE
model version with its HTML report in the database.
"""

from __future__ import annotations

import argparse
import sys
from datetime import date

from . import config, data, pipeline, report

FEATURE_VERSION = "f1"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="calibration", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--athlete", type=int, required=True)
    ap.add_argument("--test-from", type=date.fromisoformat, help="first day of the held-out test window (default: last 20%% of paired days)")
    ap.add_argument("--targets", help="path to a targets.json overriding the defaults")
    ap.add_argument("--out", help="also write the HTML report to this file")
    ap.add_argument("--dry-run", action="store_true", help="do not store the model version")
    args = ap.parse_args(argv)

    settings = config.load(args.targets)
    with data.connect() as conn:
        name = data.athlete_name(conn, args.athlete)
        features = data.load_features(conn, args.athlete, FEATURE_VERSION)
        if features.empty:
            print("No day features yet: run 'Recompute features' in the app (Calibration tab) first.", file=sys.stderr)
            return 2
        try:
            result = pipeline.run(features, settings, FEATURE_VERSION, args.test_from)
        except ValueError as e:
            print(f"Cannot calibrate: {e}", file=sys.stderr)
            return 2
        version = data.next_version(conn, args.athlete, date.today().strftime("%Y%m%d"))
        html = report.render(result, name, version)
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(html)
        if not args.dry_run:
            model_id = data.save_model(conn, args.athlete, version, result, html)
            print(f"Stored model version {version} (id {model_id}).")

    m = result.metrics
    print(f"Train {result.train_from} → {result.train_to}, test {result.test_from} → {result.test_to}")
    print(f"Strain MAE {m['strain'].get('mae', float('nan')):.2f} (target ≤ {settings.strain_mae_max})")
    print(
        f"Recovery band agreement {100 * m['recovery']['agreement']:.0f}% (target ≥ {100 * settings.recovery_band_agreement_min:.0f}%), "
        f"green-vs-red {100 * m['recovery']['green_red_rate']:.1f}% (target ≤ {100 * settings.recovery_green_red_max:.0f}%)"
    )
    print("RESULT:", "PASS" if result.passed else "FAIL")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
