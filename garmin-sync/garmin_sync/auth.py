"""Garmin sign-in for the app: password once, token back, MFA in a second step (ADR 0011, ADR 0015).

The sidecar is stateless: it never stores a token. The only thing it keeps in memory is a
login that waits for an MFA code, for a few minutes at most (ADR 0011 D5).

Nothing here may log or return the password, the MFA code, the tokens or a Garmin
response; errors are reported as fixed codes only (ADR 0011 D9).
"""

from __future__ import annotations

import logging
import os
import secrets
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

# The library's own log lines can quote Garmin responses; keep them out of our logs.
for _name in ("garminconnect", "curl_cffi", "urllib3", "requests"):
    logging.getLogger(_name).setLevel(logging.CRITICAL)


class GarminError(Exception):
    """A failure with a fixed, safe code. Never carries a Garmin payload or a secret."""

    def __init__(self, code: str, http_status: int) -> None:
        super().__init__(code)
        self.code = code
        self.http_status = http_status


AUTH_FAILED = GarminError("AUTH_FAILED", 401)  # wrong username/password, or the account is locked
AUTH_REJECTED = GarminError("AUTH_REJECTED", 401)  # a stored token Garmin no longer accepts
MFA_INVALID = GarminError("MFA_INVALID", 401)  # wrong code; the same login can try again
MFA_EXPIRED = GarminError("MFA_EXPIRED", 410)  # unknown, used up or timed out: start Connect again
RATE_LIMITED = GarminError("RATE_LIMITED", 429)
GARMIN_UNAVAILABLE = GarminError("GARMIN_UNAVAILABLE", 502)


class GarminClient(Protocol):
    """The part of garminconnect.Garmin that the sidecar uses (so tests can fake it)."""

    username: str | None
    password: str | None
    client: Any  # has dumps() -> str

    def login(self, tokenstore: str | None = None) -> tuple[str | None, Any]: ...

    def resume_login(self, client_state: dict[str, Any], mfa_code: str) -> tuple[Any, Any]: ...


ClientFactory = Callable[[str | None, str | None], GarminClient]


def real_client_factory(username: str | None, password: str | None) -> GarminClient:
    """garminconnect.Garmin, asked to return on MFA instead of prompting on a console."""
    import garminconnect  # imported here so the tests never need the network library

    return garminconnect.Garmin(username, password, return_on_mfa=True)


def classify(exc: BaseException, auth_code: GarminError = AUTH_FAILED) -> GarminError:
    """Map a garminconnect exception to a fixed code, without looking at its text."""
    if isinstance(exc, GarminError):
        return exc
    name = type(exc).__name__
    if name == "GarminConnectTooManyRequestsError":
        return RATE_LIMITED
    if name == "GarminConnectAuthenticationError":
        return auth_code
    return GARMIN_UNAVAILABLE


@dataclass
class _Pending:
    client: GarminClient
    created: float
    attempts: int = 0


class MfaStore:
    """Logins waiting for an MFA code, in memory only, under random one-time ids (ADR 0011 D5)."""

    def __init__(
        self, ttl_seconds: float, max_attempts: int, max_pending: int = 10, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self.ttl = ttl_seconds
        self.max_attempts = max_attempts
        self.max_pending = max_pending
        self._clock = clock
        self._items: dict[str, _Pending] = {}
        self._lock = threading.Lock()

    def put(self, client: GarminClient) -> str:
        with self._lock:
            self._purge()
            while len(self._items) >= self.max_pending:  # oldest first; bounds memory
                self._items.pop(next(iter(self._items)))
            mfa_id = secrets.token_urlsafe(24)
            self._items[mfa_id] = _Pending(client, self._clock())
            return mfa_id

    def take(self, mfa_id: str) -> _Pending:
        """The waiting login, counted as one attempt. Raises MFA_EXPIRED if gone."""
        with self._lock:
            self._purge()
            item = self._items.get(mfa_id)
            if item is None:
                raise MFA_EXPIRED
            item.attempts += 1
            if item.attempts >= self.max_attempts:
                self._items.pop(mfa_id, None)  # last try: whatever happens, it is used up
            return item

    def drop(self, mfa_id: str) -> None:
        with self._lock:
            self._items.pop(mfa_id, None)

    def __len__(self) -> int:
        with self._lock:
            self._purge()
            return len(self._items)

    def _purge(self) -> None:
        now = self._clock()
        for k in [k for k, v in self._items.items() if now - v.created > self.ttl]:
            del self._items[k]


@dataclass(frozen=True)
class LoginResult:
    status: str  # "CONNECTED" or "MFA_REQUIRED"
    tokens: str | None = None  # the token bundle (JSON text), only with CONNECTED
    mfa_id: str | None = None  # only with MFA_REQUIRED

    def to_json(self) -> dict[str, str]:
        body = {"status": self.status}
        if self.tokens is not None:
            body["tokens"] = self.tokens
        if self.mfa_id is not None:
            body["mfaId"] = self.mfa_id
        return body


def _forget_credentials(client: GarminClient) -> None:
    """The password (and username) are not kept for the MFA step or after login (ADR 0011 D1, D5)."""
    client.password = None
    client.username = None


class GarminAuth:
    def __init__(self, factory: ClientFactory, mfa: MfaStore) -> None:
        self._factory = factory
        self.mfa = mfa

    def login(self, username: str, password: str) -> LoginResult:
        # tokenstore=None, and GARMINTOKENS is removed at startup: the library must never read or
        # write a token file (ADR 0011 D1).
        client = self._factory(username, password)
        try:
            status, _state = client.login(None)
        except Exception as exc:  # noqa: BLE001 - every failure becomes a fixed code
            _forget_credentials(client)
            raise classify(exc) from None
        if status == "needs_mfa":
            _forget_credentials(client)
            return LoginResult("MFA_REQUIRED", mfa_id=self.mfa.put(client))
        _forget_credentials(client)
        return LoginResult("CONNECTED", tokens=client.client.dumps())

    def resume(self, mfa_id: str, code: str) -> LoginResult:
        item = self.mfa.take(mfa_id)
        try:
            # The same instance holds the pending session, so the state argument is not needed.
            item.client.resume_login({}, code)
        except Exception as exc:  # noqa: BLE001
            err = classify(exc, auth_code=MFA_INVALID)
            if err is not MFA_INVALID:
                self.mfa.drop(mfa_id)
            raise err from None
        self.mfa.drop(mfa_id)
        return LoginResult("CONNECTED", tokens=item.client.client.dumps())


def mfa_store_from_env() -> MfaStore:
    # [configurable] 5 minutes: long enough to read an e-mail or SMS code, short enough not to
    # leave a half-finished login lying around (ADR 0011 D5).
    ttl = float(os.environ.get("MFA_TTL_SECONDS", "300"))
    # [configurable] 3 tries for a mistyped code before Connect starts over; more invites
    # guessing and could make Garmin lock the account.
    attempts = int(os.environ.get("MFA_MAX_ATTEMPTS", "3"))
    return MfaStore(ttl, attempts)
