"""Fetching days from Garmin Connect (stage 1b-4 PR 2, ADR 0015 D5).

Garmin has no official consumer API: this wraps the unofficial `garminconnect` library, so
everything that can break lives here and the Java app never depends on it. The payloads are
passed on as Garmin returns them (only trimmed to the parts the app reads); the app turns
them into the same raw rows as a Garmin account export.

The sidecar stays stateless: the token bundle arrives in the request and the (possibly
refreshed) bundle goes back in the response. Nothing is written to disk (ADR 0011 D1, D4).
"""

from __future__ import annotations

import base64
from collections.abc import Callable
from datetime import date
from typing import Any

from .auth import AUTH_REJECTED, GarminClient, classify, real_client_factory

WELLNESS_FIT_PATH = "/download-service/files/wellness/{day}"  # the day's monitoring FIT files, zipped


class SyncUnavailableError(RuntimeError):
    """Kept for compatibility with the Phase 0 interface."""


def _is_not_found(exc: BaseException) -> bool:
    return type(exc).__name__ == "GarminConnectNotFoundError"


def _b64(data: bytes | None) -> str | None:
    return None if not data else base64.b64encode(data).decode("ascii")


class GarminAdapter:
    """Fetches days for one account, given its token bundle."""

    def fetch_days(self, tokens: str, days: list[date]) -> dict[str, Any]:  # pragma: no cover - interface
        raise NotImplementedError


class NotConfiguredAdapter(GarminAdapter):
    def fetch_days(self, tokens: str, days: list[date]) -> dict[str, Any]:
        raise SyncUnavailableError("Garmin sync is not configured")


class GarminConnectAdapter(GarminAdapter):
    def __init__(self, factory: Callable[[str | None, str | None], GarminClient] = real_client_factory) -> None:
        self._factory = factory

    def fetch_days(self, tokens: str, days: list[date]) -> dict[str, Any]:
        """Everything the app needs for {days}, plus the token bundle to store.

        A part Garmin has no data for (404) is left out and named in `missing`; any other
        failure stops the whole fetch with a fixed code, so a half-fetched morning is never
        imported as if it were complete.
        """
        client = self._factory(None, None)
        try:
            # Tokens as JSON text: loaded in memory, refreshed if about to expire, never saved.
            client.login(tokens)
        except Exception as exc:  # noqa: BLE001
            raise classify(exc, auth_code=AUTH_REJECTED) from None

        missing: list[str] = []

        def get(label: str, call: Callable[[], Any]) -> Any:
            try:
                return call()
            except Exception as exc:  # noqa: BLE001
                if _is_not_found(exc):
                    missing.append(label)
                    return None
                raise classify(exc, auth_code=AUTH_REJECTED) from None

        out_days = []
        for d in days:
            ds = d.isoformat()
            summary = get(f"summary:{ds}", lambda ds=ds: client.get_user_summary(ds))
            sleep = get(f"sleep:{ds}", lambda ds=ds: client.get_sleep_data(ds))
            hrv = get(f"hrv:{ds}", lambda ds=ds: client.get_hrv_data(ds))
            wellness = get(f"wellnessFit:{ds}", lambda ds=ds: client.download(WELLNESS_FIT_PATH.format(day=ds)))
            out_days.append(
                {
                    "date": ds,
                    "summary": summary if isinstance(summary, dict) else None,
                    # Only the nightly summary: the per-minute arrays are large and unused.
                    "sleep": (sleep or {}).get("dailySleepDTO") if isinstance(sleep, dict) else None,
                    "hrv": (hrv or {}).get("hrvSummary") if isinstance(hrv, dict) else None,
                    "wellnessFit": _b64(wellness),
                }
            )

        activities = []
        if days:
            listed = get(
                "activities",
                lambda: client.get_activities_by_date(min(days).isoformat(), max(days).isoformat()),
            )
            for a in listed or []:
                if not isinstance(a, dict) or a.get("activityId") is None:
                    continue
                aid = a["activityId"]
                fit = get(f"activityFit:{aid}", lambda aid=aid: client.download_activity(aid, _original_format(client)))
                activities.append({"summary": a, "fit": _b64(fit)})

        return {"tokens": client.client.dumps(), "days": out_days, "activities": activities, "missing": missing}


def _original_format(client: GarminClient) -> Any:
    """The library's ORIGINAL download format (the activity's FIT file, zipped)."""
    fmt = getattr(type(client), "ActivityDownloadFormat", None)
    return fmt.ORIGINAL if fmt is not None else "ORIGINAL"
