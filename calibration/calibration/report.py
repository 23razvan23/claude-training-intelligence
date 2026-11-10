"""Self-contained HTML calibration report: inline CSS, base64 PNG charts, no scripts."""

from __future__ import annotations

import base64
import html
import io
import math

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

from .pipeline import Result  # noqa: E402

CSS = """
body{font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#111827;background:#fff;margin:0;padding:16px;max-width:980px}
h1{font-size:1.4rem;margin:0 0 4px}h2{font-size:1.1rem;margin:28px 0 8px;border-bottom:1px solid #e5e7eb;padding-bottom:4px}
.muted{color:#6b7280}.pass{color:#047857;font-weight:600}.fail{color:#b91c1c;font-weight:600}
table{border-collapse:collapse;margin:8px 0;display:block;max-width:100%;overflow-x:auto}
td,th{border:1px solid #e5e7eb;padding:4px 8px;text-align:right}th{background:#f9fafb}
td:first-child,th:first-child{text-align:left}.verdict{font-size:1.2rem;padding:10px 12px;border-radius:8px;margin:12px 0}
.ok{background:#ecfdf5}.no{background:#fef2f2}img{max-width:100%;height:auto}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media (max-width:640px){.grid{grid-template-columns:1fr}}
"""


def _png(fig) -> str:
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return f'<img alt="" src="data:image/png;base64,{base64.b64encode(buf.getvalue()).decode()}">'


def _scatter(y, p, label, lim):
    fig, ax = plt.subplots(figsize=(4.2, 4.0))
    ax.scatter(y, p, s=12, alpha=0.7, color="#0f766e")
    ax.plot(lim, lim, color="#9ca3af", lw=1)
    ax.set_xlim(lim)
    ax.set_ylim(lim)
    ax.set_xlabel(f"Whoop {label}")
    ax.set_ylabel(f"Garmin-derived {label}")
    ax.set_title(f"{label}: ours vs Whoop")
    return _png(fig)


def _bland_altman(y, p, label):
    y = np.asarray(y, dtype=float)
    p = np.asarray(p, dtype=float)
    mean = (y + p) / 2
    diff = p - y
    bias = diff.mean()
    sd = diff.std(ddof=1) if len(diff) > 1 else 0
    fig, ax = plt.subplots(figsize=(4.2, 4.0))
    ax.scatter(mean, diff, s=12, alpha=0.7, color="#2563eb")
    for v, style in ((bias, "-"), (bias + 1.96 * sd, "--"), (bias - 1.96 * sd, "--")):
        ax.axhline(v, color="#6b7280", ls=style, lw=1)
    ax.set_xlabel("mean of both")
    ax.set_ylabel("ours − Whoop")
    ax.set_title(f"{label}: Bland–Altman")
    return _png(fig)


def _timeseries(days):
    fig, ax = plt.subplots(figsize=(8.8, 3.0))
    ax.plot(days.index, days["whoop_recovery"], label="Whoop", color="#6b7280", lw=1.5)
    ax.plot(days.index, days["recovery"], label="Garmin-derived", color="#0f766e", lw=1.5)
    for v in (33, 67):
        ax.axhline(v, color="#e5e7eb", lw=1)
    ax.set_ylim(0, 100)
    ax.set_ylabel("recovery %")
    ax.legend(loc="lower left", frameon=False)
    ax.set_title("Recovery over the test window")
    fig.autofmt_xdate()
    return _png(fig)


def _f(v, digits=2):
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "–"
    return f"{v:.{digits}f}"


def _pct(v):
    return "–" if v is None or (isinstance(v, float) and math.isnan(v)) else f"{100 * v:.0f}%"


def render(result: Result, athlete_name: str, version: str) -> str:
    m = result.metrics
    rec, st = m["recovery"], m["strain"]
    checks = m["checks"]
    t = m["targets"]
    e = html.escape

    def check_row(name, actual, target, ok):
        cls = "pass" if ok else "fail"
        return f"<tr><td>{name}</td><td>{actual}</td><td>{target}</td><td class='{cls}'>{'PASS' if ok else 'FAIL'}</td></tr>"

    parts = [f"<!doctype html><html lang='en'><head><meta charset='utf-8'><title>Calibration {e(version)}</title><style>{CSS}</style></head><body>"]
    parts.append(f"<h1>Calibration report {e(version)}</h1>")
    parts.append(
        f"<div class='muted'>{e(athlete_name)} · feature set {e(result.coefficients['feature_version'])} · "
        f"train {result.train_from} → {result.train_to} · test {result.test_from} → {result.test_to}</div>"
    )
    verdict = "All acceptance targets met." if result.passed else "Not all acceptance targets are met — see below."
    parts.append(f"<div class='verdict {'ok' if result.passed else 'no'}'>{verdict}</div>")

    parts.append("<h2>Acceptance targets (test window)</h2><table><tr><th>Check</th><th>Ours</th><th>Target</th><th></th></tr>")
    parts.append(check_row("Strain MAE", _f(st.get("mae")), f"≤ {t['strain_mae_max']}", checks["strain_mae"]))
    parts.append(check_row("Recovery band agreement", _pct(rec["agreement"]), f"≥ {_pct(t['recovery_band_agreement_min'])}", checks["recovery_band_agreement"]))
    parts.append(check_row("Green-vs-red disagreement", _pct(rec["green_red_rate"]), f"≤ {_pct(t['recovery_green_red_max'])}", checks["recovery_green_red"]))
    parts.append(
        "</table><p class='muted'>Targets are starting values from the spec ([configurable] in targets.json). " "The test window was never seen while fitting.</p>"
    )

    parts.append("<h2>Error metrics</h2><table><tr><th>Score</th><th>n</th><th>MAE</th><th>Spearman</th><th>Bias</th><th>95% limits</th></tr>")
    for key, label in (
        ("strain", "Strain (0–21)"),
        ("recovery", "Recovery (%)"),
        ("sleep_need", "Sleep need (min)"),
        ("sleep_performance", "Sleep performance (%)"),
        ("recovery_challenger", "Recovery, challenger (GBM)"),
    ):
        s = m[key]
        parts.append(
            f"<tr><td>{label}</td><td>{s.get('n', 0)}</td><td>{_f(s.get('mae'))}</td><td>{_f(s.get('spearman'))}</td>"
            f"<td>{_f(s.get('bias'))}</td><td>{_f(s.get('loa_low'))} … {_f(s.get('loa_high'))}</td></tr>"
        )
    parts.append("</table>")

    parts.append("<h2>Recovery bands</h2><p>Rows: Whoop's band. Columns: ours.</p><table><tr><th></th><th>GREEN</th><th>YELLOW</th><th>RED</th></tr>")
    for w in ("GREEN", "YELLOW", "RED"):
        row = rec["confusion"][w]
        parts.append(f"<tr><td>{w}</td><td>{row['GREEN']}</td><td>{row['YELLOW']}</td><td>{row['RED']}</td></tr>")
    parts.append("</table>")

    d = result.test_days
    parts.append("<h2>Recovery</h2>" + _timeseries(d))
    parts.append(
        "<div class='grid'>"
        + _scatter(d["whoop_recovery"], d["recovery"], "recovery", (0, 100))
        + _bland_altman(d["whoop_recovery"], d["recovery"], "recovery")
        + "</div>"
    )
    sd = d[["whoop_strain", "strain_pred"]].dropna()
    if len(sd):
        parts.append(
            "<h2>Strain</h2><div class='grid'>"
            + _scatter(sd["whoop_strain"], sd["strain_pred"], "strain", (0, 21))
            + _bland_altman(sd["whoop_strain"], sd["strain_pred"], "strain")
            + "</div>"
        )

    c = result.coefficients
    parts.append(
        "<h2>Model</h2><h3>Recovery terms</h3><p>Score = intercept + Σ weight · (sign · value − center) / scale. "
        "Weights are ≥ 0 by construction (monotonic).</p><table><tr><th>Feature</th><th>Sign</th><th>Weight (points per SD)</th></tr>"
    )
    parts.append(f"<tr><td>intercept</td><td></td><td>{_f(c['recovery']['intercept'], 1)}</td></tr>")
    for term in c["recovery"]["terms"]:
        parts.append(f"<tr><td>{e(term['feature'])}</td><td>{'+' if term['sign'] > 0 else '−'}</td><td>{_f(term['weight'], 2)}</td></tr>")
    parts.append("</table>")
    s = c["strain"]
    parts.append(
        f"<h3>Strain</h3><p>strain = {_f(s['c'])} + {_f(s['a'])} · ln(1 + {s['b']:.5f} · load), "
        f"load = <code>{e(s['load'])}</code> (time-series CV MAE: " + ", ".join(f"{e(k)} {_f(v)}" for k, v in st["candidates"].items()) + ").</p>"
    )
    sl = c["sleep"]
    parts.append(
        f"<h3>Sleep</h3><p>need = {_f(sl['base_min'], 0)} min + {_f(sl['per_strain_min'], 1)} min × yesterday's strain "
        f"+ {_f(sl['per_shortfall'], 2)} × {sl['shortfall_days']}-day shortfall below {_f(sl['shortfall_reference_min'], 0)} min.</p>"
    )

    parts.append("<h2>Data</h2><table><tr><th>Item</th><th>Days</th></tr>")
    for k, v in result.exclusions.items():
        parts.append(f"<tr><td>{e(k.replace('_', ' '))}</td><td>{v}</td></tr>")
    parts.append("</table><p class='muted'>Days without a complete input set are excluded, never imputed.</p>")

    worst = d.assign(err=(d["recovery"] - d["whoop_recovery"]).abs()).sort_values("err", ascending=False).head(10)
    parts.append("<h2>Largest recovery disagreements</h2><table><tr><th>Day</th><th>Whoop</th><th>Ours</th><th>Biggest contributors</th></tr>")
    term_names = [t["feature"] for t in c["recovery"]["terms"]]
    for day, row in worst.iterrows():
        contrib = sorted(((n, row[n]) for n in term_names), key=lambda kv: -abs(kv[1]))[:2]
        text = ", ".join(f"{e(n)} {v:+.0f}" for n, v in contrib)
        parts.append(f"<tr><td>{day.date()}</td><td>{row['whoop_recovery']:.0f}</td><td>{row['recovery']:.0f}</td><td>{text}</td></tr>")
    parts.append("</table>")

    if result.notes:
        parts.append("<h2>Notes</h2><ul>" + "".join(f"<li>{e(n)}</li>" for n in result.notes) + "</ul>")
    parts.append(
        "<p class='muted'>Known gap: Garmin's overnight HRV is a whole-night average; Whoop weights the last deep-sleep "
        "period. The model learns this offset rather than hand-tuning it.</p></body></html>"
    )
    return "".join(parts)
