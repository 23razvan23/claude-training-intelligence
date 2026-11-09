"""Agreement metrics between our scores and Whoop's."""

from __future__ import annotations

import numpy as np
from scipy import stats

BANDS = ("GREEN", "YELLOW", "RED")


def summarize(y: np.ndarray, p: np.ndarray) -> dict:
    """n, MAE, Spearman, Bland–Altman bias and 95% limits of agreement (ours − Whoop)."""
    y = np.asarray(y, dtype=float)
    p = np.asarray(p, dtype=float)
    n = len(y)
    if n == 0:
        return {"n": 0}
    diff = p - y
    bias = float(diff.mean())
    sd = float(diff.std(ddof=1)) if n > 1 else 0.0
    rho = float(stats.spearmanr(y, p).statistic) if n > 2 and np.ptp(y) > 0 and np.ptp(p) > 0 else float("nan")
    return {
        "n": n,
        "mae": float(np.abs(diff).mean()),
        "spearman": rho,
        "bias": bias,
        "loa_low": bias - 1.96 * sd,
        "loa_high": bias + 1.96 * sd,
    }


def band(value: float, green_min: int, red_max: int) -> str:
    if value >= green_min:
        return "GREEN"
    if value <= red_max:
        return "RED"
    return "YELLOW"


def band_agreement(y: np.ndarray, p: np.ndarray, green_min: int, red_max: int) -> dict:
    """Share of days in the same band, the 3×3 confusion matrix (rows = Whoop), and green-vs-red rate."""
    yb = [band(v, green_min, red_max) for v in y]
    pb = [band(v, green_min, red_max) for v in p]
    n = len(yb)
    matrix = {w: {o: 0 for o in BANDS} for w in BANDS}
    for w, o in zip(yb, pb, strict=True):
        matrix[w][o] += 1
    same = sum(matrix[b][b] for b in BANDS)
    green_red = matrix["GREEN"]["RED"] + matrix["RED"]["GREEN"]
    return {
        "n": n,
        "agreement": same / n if n else float("nan"),
        "green_red_rate": green_red / n if n else float("nan"),
        "confusion": matrix,
    }
