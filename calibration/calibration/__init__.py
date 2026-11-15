"""Module 1 calibration job (Phase 1).

Reads paired Garmin + Whoop days from the database, fits the strain, recovery
and sleep models on a time-based split, and writes model_coefficients_vN.json
plus an HTML validation report. Nothing here runs daily.
"""

__version__ = "0.6.0"  # also the package version (pyproject reads it); stored as coefficients.calibration_version
