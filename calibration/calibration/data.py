"""Database access. Connection settings come from the standard libpq environment
variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD)."""

from __future__ import annotations

import json
from decimal import Decimal

import pandas as pd


def connect():
    import psycopg

    return psycopg.connect("")


def athlete_name(conn, athlete_id: int) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT name FROM athlete WHERE id = %s", (athlete_id,))
        row = cur.fetchone()
    if not row:
        raise SystemExit(f"No athlete with id {athlete_id}")
    return row[0]


def load_features(conn, athlete_id: int, feature_version: str) -> pd.DataFrame:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT * FROM day_features WHERE athlete_id = %s AND feature_version = %s ORDER BY cycle_date",
            (athlete_id, feature_version),
        )
        cols = [c.name for c in cur.description]
        rows = cur.fetchall()
    df = pd.DataFrame(rows, columns=cols)
    for c in df.columns:
        if df[c].map(lambda v: isinstance(v, Decimal)).any():
            df[c] = pd.to_numeric(df[c].map(lambda v: float(v) if v is not None else None))
    return df


def next_version(conn, athlete_id: int, today: str) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM model_version WHERE athlete_id = %s", (athlete_id,))
        n = cur.fetchone()[0] + 1
    return f"m{n}-{today}"


def _json_default(o):
    if isinstance(o, float | Decimal):
        return float(o)
    return str(o)


def _clean(o):
    """NaN is not valid JSON for PostgreSQL; store it as null."""
    if isinstance(o, float) and o != o:
        return None
    if isinstance(o, dict):
        return {k: _clean(v) for k, v in o.items()}
    if isinstance(o, list):
        return [_clean(v) for v in o]
    return o


def save_model(conn, athlete_id: int, version: str, result, report_html: str) -> int:
    with conn.cursor() as cur:
        cur.execute(
            """INSERT INTO model_version (athlete_id, version, status, feature_version, train_from, train_to,
                   test_from, test_to, coefficients, metrics, passed, report_html)
               VALUES (%s, %s, 'CANDIDATE', %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s, %s) RETURNING id""",
            (
                athlete_id,
                version,
                result.coefficients["feature_version"],
                result.train_from,
                result.train_to,
                result.test_from,
                result.test_to,
                json.dumps(_clean(result.coefficients), default=_json_default),
                json.dumps(_clean({**result.metrics, "exclusions": result.exclusions}), default=_json_default),
                result.passed,
                report_html,
            ),
        )
        new_id = cur.fetchone()[0]
    conn.commit()
    return new_id
