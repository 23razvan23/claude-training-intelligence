#!/usr/bin/env python3
"""Summarise Whoop and Garmin export files before trusting the importers.

Read-only and standard library only. Prints columns / JSON keys, row counts,
date coverage, missing days and numeric ranges. It never prints free-text
values (journal notes, names), only counts, so the output is safe to paste
into an issue or a phase review.

Works with Python 3.9+ (the python3 that ships with macOS).

Usage:
    python3 -I tools/inspect_exports.py my_whoop_data.zip garmin_export.zip
    python3 -I tools/inspect_exports.py physiological_cycles.csv sleeps.csv
    python3 -I tools/inspect_exports.py --files garmin_export.zip   # also list file names

Short upper-case codes (e.g. TOTAL, HIGHEST, HRV) and true/false flags are
printed as values; all other text is reduced to a count of distinct values.
--files lists entry names with every digit run masked as "#".

Compare the output with docs/adr/0004-export-format-assumptions.md and fix
WhoopColumns.java / GarminFields.java where they differ.
"""

from __future__ import annotations

import csv
import io
import json
import re
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

DATE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})")
GARMIN_PATTERNS = {
    "summarizedActivities": re.compile(r"(?i)summarizedActivities.*\.json$"),
    "sleepData": re.compile(r"(?i)sleepData.*\.json$"),
    "UDSFile (daily summary)": re.compile(r"(?i)UDSFile.*\.json$"),
    "healthStatusData (HRV)": re.compile(r"(?i)(healthStatusData|hrvStatus).*\.json$"),
}
MAX_KEYS = 200
ENUM_RE = re.compile(r"^(?:[A-Z][A-Z0-9_]{0,31}|true|false|True|False)$")
MAX_ENUM_VALUES = 10


@dataclass
class ColumnStats:
    non_empty: int = 0
    numeric: int = 0
    minimum: float | None = None
    maximum: float | None = None
    total: float = 0.0
    dates: set[date] = field(default_factory=set)
    distinct: set[str] = field(default_factory=set)

    def add(self, raw: str | None) -> None:
        if raw is None:
            return
        v = str(raw).strip()
        if not v:
            return
        self.non_empty += 1
        num = _number(v)
        if num is not None:
            self.numeric += 1
            self.total += num
            self.minimum = num if self.minimum is None else min(self.minimum, num)
            self.maximum = num if self.maximum is None else max(self.maximum, num)
        else:
            d = _date(v)
            if d:
                self.dates.add(d)
            elif len(self.distinct) < 1000:
                self.distinct.add(v)

    def describe(self) -> str:
        if self.non_empty == 0:
            return "empty"
        if self.numeric == self.non_empty:
            mean = self.total / self.numeric
            return f"numeric  min={_fmt(self.minimum)} max={_fmt(self.maximum)} mean={_fmt(mean)}"
        if self.dates and len(self.dates) >= self.non_empty * 0.5:
            return f"dates    {min(self.dates)} .. {max(self.dates)} ({len(self.dates)} distinct days)"
        n = len(self.distinct)
        if 0 < n <= MAX_ENUM_VALUES and all(ENUM_RE.match(v) for v in self.distinct):
            return "values   " + ", ".join(sorted(self.distinct))
        return f"text     {n}{'+' if n >= 1000 else ''} distinct values"


def _number(v: str) -> float | None:
    try:
        return float(v.replace("%", "").replace(",", ""))
    except ValueError:
        return None


def _date(v: str) -> date | None:
    m = DATE_RE.match(v)
    if not m:
        return None
    try:
        return date.fromisoformat(m.group(1))
    except ValueError:
        return None


def _fmt(x: float | None) -> str:
    return "-" if x is None else (f"{x:.0f}" if abs(x) >= 1000 else f"{x:.2f}")


def coverage(days: set[date]) -> list[str]:
    if not days:
        return ["  no dates found"]
    lo, hi = min(days), max(days)
    span = (hi - lo).days + 1
    missing = []
    d = lo
    while d <= hi:
        if d not in days:
            start = d
            while d <= hi and d not in days:
                d += timedelta(days=1)
            missing.append((start, d - timedelta(days=1)))
        d += timedelta(days=1)
    out = [f"  coverage {lo} .. {hi}: {len(days)}/{span} days present, {span - len(days)} missing"]
    for a, b in missing[:15]:
        out.append(f"    missing {a}" + ("" if a == b else f" .. {b} ({(b - a).days + 1} days)"))
    if len(missing) > 15:
        out.append(f"    ... and {len(missing) - 15} more gaps")
    return out


# ------------------------------------------------------------------ CSV (Whoop)


def inspect_csv(name: str, text: str) -> list[str]:
    reader = csv.reader(io.StringIO(text.lstrip("﻿")))
    try:
        headers = next(reader)
    except StopIteration:
        return [f"== {name}: empty file"]
    stats = [ColumnStats() for _ in headers]
    rows = 0
    for row in reader:
        if not any(c.strip() for c in row):
            continue
        rows += 1
        for i, cell in enumerate(row[: len(headers)]):
            stats[i].add(cell)
    out = [f"== {name}: {rows} rows, {len(headers)} columns"]
    width = max((len(h) for h in headers), default=0)
    for h, s in zip(headers, stats):
        out.append(f"  {h:<{width}}  {s.non_empty:>6} filled  {s.describe()}")
    first = next((s for h, s in zip(headers, stats) if "cycle start" in h.lower()), None)
    if first is None and stats:
        first = stats[0]
    if first is not None:
        out.extend(coverage(first.dates))
    return out


# ------------------------------------------------------------------ JSON (Garmin)


def _records(root) -> list[dict]:
    if isinstance(root, list):
        recs: list[dict] = []
        for item in root:
            if isinstance(item, dict) and isinstance(item.get("summarizedActivitiesExport"), list):
                recs.extend(r for r in item["summarizedActivitiesExport"] if isinstance(r, dict))
            elif isinstance(item, dict):
                recs.append(item)
        return recs
    if isinstance(root, dict):
        return [root]
    return []


def _flatten(d: dict, prefix: str = "") -> list[tuple[str, object]]:
    """(path, value) pairs. Objects nest as a/b; lists of objects as a[]/b (one pair per element)."""
    out: list[tuple[str, object]] = []
    for k, v in d.items():
        key = f"{prefix}{k}"
        if isinstance(v, dict):
            out.extend(_flatten(v, key + "/"))
        elif isinstance(v, list) and v and all(isinstance(x, dict) for x in v):
            out.append((key + "[]", v))
            for item in v:
                out.extend(_flatten(item, key + "[]/"))
        else:
            out.append((key, v))
    return out


def _record_day(r: dict) -> date | None:
    cd = r.get("calendarDate")
    if isinstance(cd, dict):
        cd = cd.get("date")
    if isinstance(cd, str):
        return _date(cd)
    ms = r.get("startTimeGmt") or r.get("beginTimestamp")
    if isinstance(ms, (int, float)):
        return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).date()
    return None


RPE_KEYS = ("workoutRpe", "workoutFeel")
MAX_DISTINCT_SHOWN = 25


def _num_or_text(v):
    """A number stays a number; anything else is only counted, never shown."""
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    return None


def _show(x: float) -> str:
    return str(int(x)) if x == int(x) else f"{x:g}"


def rpe_feel_summary(recs: list[dict]) -> list[str]:
    """Counts and distinct values of workoutRpe / workoutFeel (stage 2-1b seed, ADR 0020 D7).

    Only numbers and how often they occur; no dates, ids, sport names or text.
    """
    if not any(k in r for r in recs for k in RPE_KEYS):
        return []
    out = [f"== Per-activity effort fields (workoutRpe / workoutFeel), {len(recs)} activity records"]
    values: dict = {}
    for key in RPE_KEYS:
        nums: Counter = Counter()
        other = missing = 0
        for r in recs:
            v = r.get(key)
            if v is None or v == "":
                missing += 1
                continue
            n = _num_or_text(v)
            if n is None:
                other += 1
            else:
                nums[n] += 1
        values[key] = nums
        total = sum(nums.values())
        out.append(f"  {key}: {total} numeric, {missing} empty or missing, {other} not a number")
        if nums:
            lo, hi = min(nums), max(nums)
            tens = sum(c for x, c in nums.items() if x % 10 == 0)
            out.append(f"    min={_show(lo)} max={_show(hi)}; multiples of 10: {tens} of {total}")
            shown = sorted(nums.items())[:MAX_DISTINCT_SHOWN]
            out.append("    value x count: " + ", ".join(f"{_show(x)}x{c}" for x, c in shown))
            if len(nums) > MAX_DISTINCT_SHOWN:
                out.append(f"    ... and {len(nums) - MAX_DISTINCT_SHOWN} more distinct values")
    pairs: Counter = Counter()
    for r in recs:
        a, b = _num_or_text(r.get("workoutRpe")), _num_or_text(r.get("workoutFeel"))
        if a is not None or b is not None:
            pairs[(a, b)] += 1
    both = sum(c for (a, b), c in pairs.items() if a is not None and b is not None)
    out.append(f"  records with both: {both}; with a value in either: {sum(pairs.values())}")
    if both:
        out.append(
            "  (rpe, feel) x count: "
            + ", ".join(
                f"({_show(a)}, {_show(b)})x{c}"
                for (a, b), c in sorted(pairs.items())
                if a is not None and b is not None
            )[:1500]
        )
    return out


def inspect_json_group(label: str, blobs: list[tuple[str, bytes]]) -> list[str]:
    recs: list[dict] = []
    bad = 0
    for _, data in blobs:
        try:
            recs.extend(_records(json.loads(data)))
        except (json.JSONDecodeError, UnicodeDecodeError):
            bad += 1
    out = [f"== {label}: {len(blobs)} files, {len(recs)} records" + (f", {bad} unreadable" if bad else "")]
    stats: dict[str, ColumnStats] = {}
    presence: Counter[str] = Counter()
    days: set[date] = set()
    for r in recs:
        d = _record_day(r)
        if d:
            days.add(d)
        for k, v in _flatten(r):
            if isinstance(v, list):
                presence[k if k.endswith("[]") else k + "[]"] += 1
                continue
            presence[k] += 1
            stats.setdefault(k, ColumnStats()).add(None if v is None else str(v))
    width = max((len(k) for k, _ in presence.most_common(MAX_KEYS)), default=0)
    for k, n in presence.most_common(MAX_KEYS):
        desc = stats[k].describe() if k in stats else "list"
        out.append(f"  {k:<{width}}  {n:>6} present  {desc}")
    if len(presence) > MAX_KEYS:
        out.append(f"  ... and {len(presence) - MAX_KEYS} more keys")
    out.extend(coverage(days))
    out.extend(rpe_feel_summary(recs))
    return out


# ------------------------------------------------------------------ ZIPs


def _is_fit(name: str) -> bool:
    return name.lower().endswith(".fit")


def list_files(names: list[str]) -> list[str]:
    """Entry names per folder, digit runs masked, so dates and ids are not shown."""
    masked = Counter()
    for n in names:
        if "Uploaded-Files" in n or _is_fit(n):
            continue
        masked[re.sub(r"\d+", "#", n)] += 1
    out = [f"== File names ({len(masked)} distinct patterns, digits masked)"]
    for name, c in sorted(masked.items()):
        out.append(f"  {c:>5}  {name}")
    return out


def inspect_zip(path: str, show_files: bool = False) -> list[str]:
    out = [f"#### {path}"]
    with zipfile.ZipFile(path) as zf:
        names = [n for n in zf.namelist() if not n.endswith("/")]
        csvs = [n for n in names if n.lower().endswith(".csv")]
        groups: dict[str, list[tuple[str, bytes]]] = {k: [] for k in GARMIN_PATTERNS}
        fit_count = 0
        nested_zips = 0
        for n in names:
            for label, pat in GARMIN_PATTERNS.items():
                if pat.search(n):
                    groups[label].append((n, zf.read(n)))
            if _is_fit(n):
                fit_count += 1
            elif n.lower().endswith(".zip"):
                nested_zips += 1
                with zipfile.ZipFile(io.BytesIO(zf.read(n))) as inner:
                    fit_count += sum(1 for m in inner.namelist() if _is_fit(m))
        folders = Counter(n.rsplit("/", 1)[0] if "/" in n else "." for n in names)
        out.append(f"{len(names)} files. Top folders:")
        for folder, c in folders.most_common(12):
            out.append(f"  {c:>6}  {folder}")
        for n in csvs:
            out.extend(inspect_csv(n, zf.read(n).decode("utf-8", errors="replace")))
        for label, blobs in groups.items():
            if blobs:
                out.extend(inspect_json_group(label, blobs))
        if any(groups.values()) or fit_count:
            out.append(f"== FIT files: {fit_count} (including {nested_zips} nested ZIPs)")
            for label, blobs in groups.items():
                if not blobs:
                    out.append(f"  WARNING: no {label} files matched; the importer will not read this data")
        if show_files:
            out.extend(list_files(names))
    return out


def main(argv: list[str]) -> int:
    show_files = "--files" in argv
    paths = [a for a in argv if a != "--files"]
    if not paths:
        print(__doc__)
        return 2
    for path in paths:
        try:
            if zipfile.is_zipfile(path):
                lines = inspect_zip(path, show_files)
            elif path.lower().endswith(".csv"):
                with open(path, encoding="utf-8", errors="replace") as f:
                    lines = inspect_csv(path, f.read())
            elif path.lower().endswith(".json"):
                with open(path, "rb") as f:
                    lines = inspect_json_group(path, [(path, f.read())])
            else:
                lines = [f"#### {path}: not a ZIP, CSV or JSON file, skipped"]
        except (OSError, zipfile.BadZipFile) as e:
            lines = [f"#### {path}: cannot read ({e})"]
        print("\n".join(lines))
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
