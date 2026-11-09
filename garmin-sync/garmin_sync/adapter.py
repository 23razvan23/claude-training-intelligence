"""The sync adapter boundary.

Garmin has no official consumer API; the first real implementation (Phase 1)
will wrap the unofficial `garminconnect` / `garth` libraries. Everything that
can break lives behind this interface so the Java app never depends on it.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import date


class SyncUnavailableError(RuntimeError):
    """Raised when the upstream service cannot be reached or rejects us."""


@dataclass(frozen=True)
class DayBundle:
    """Raw payloads for one calendar day, passed to the app unmodified."""

    day: date
    daily_summary: dict | None
    sleep: dict | None
    hrv: dict | None
    activities: list[dict]


class GarminAdapter(ABC):
    @abstractmethod
    def fetch_day(self, day: date) -> DayBundle:
        """Fetch everything for one day. Raises SyncUnavailableError on failure."""

    @abstractmethod
    def fetch_fit(self, activity_id: str) -> bytes:
        """Download the original FIT file for an activity."""


class NotConfiguredAdapter(GarminAdapter):
    """Default until Phase 1: every call says sync is not available yet."""

    def fetch_day(self, day: date) -> DayBundle:
        raise SyncUnavailableError("Garmin sync is not implemented until Phase 1; use the export or FIT upload.")

    def fetch_fit(self, activity_id: str) -> bytes:
        raise SyncUnavailableError("Garmin sync is not implemented until Phase 1; use the export or FIT upload.")
