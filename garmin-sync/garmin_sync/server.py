"""The sidecar's HTTP server (standard library only).

- GET /health: no secret needed; says nothing about any account.
- POST /auth/login {username, password} and POST /auth/mfa {mfaId, code}: need the
  X-Sync-Secret header (ADR 0011 D4). They answer with the token bundle or a fixed error code.

The sidecar is internal to the Compose network (`expose`, never `ports`). It keeps no token.
"""

from __future__ import annotations

import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .adapter import GarminAdapter, NotConfiguredAdapter
from .auth import GarminAuth, GarminError, mfa_store_from_env, real_client_factory

VERSION = "0.2.0"
SECRET_HEADER = "X-Sync-Secret"
MAX_BODY_BYTES = 64 * 1024  # a login or MFA body is a few hundred bytes; refuse anything large


def safe_request_summary(method: str | None, path: str | None) -> str:
    """Method and path for a log line, with any query string or fragment removed."""
    clean = (path or "").split("?", 1)[0].split("#", 1)[0]
    return f"{method or '-'} {clean or '-'}"


def secret_matches(expected: str | None, given: str | None) -> bool:
    """Constant-time comparison; an unset secret matches nothing (ADR 0011 D4)."""
    if not expected or given is None:
        return False
    return hmac.compare_digest(expected.encode(), given.encode())


class BadRequestError(Exception):
    pass


def _strings(body: dict, *names: str) -> list[str]:
    values = []
    for n in names:
        v = body.get(n)
        if not isinstance(v, str) or not v.strip() or len(v) > 1024:
            raise BadRequestError(n)
        values.append(v)
    return values


def make_handler(adapter: GarminAdapter, auth: GarminAuth | None = None, secret: str | None = None):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 (stdlib naming)
            if self.path == "/health":
                body = {
                    "status": "UP",
                    "version": VERSION,
                    "adapter": type(adapter).__name__,
                    "syncAvailable": not isinstance(adapter, NotConfiguredAdapter),
                    "secretConfigured": bool(secret),
                }
                self._json(200, body)
            else:
                self._json(404, {"error": "NOT_FOUND"})

        def do_POST(self) -> None:  # noqa: N802
            routes = {"/auth/login": self._login, "/auth/mfa": self._mfa}
            route = routes.get(self.path)
            if route is None:
                self._json(404, {"error": "NOT_FOUND"})
                return
            if not secret:
                self._json(503, {"error": "NOT_CONFIGURED"})
                return
            if not secret_matches(secret, self.headers.get(SECRET_HEADER)):
                self._json(401, {"error": "BAD_SECRET"})
                return
            if auth is None:
                self._json(503, {"error": "NOT_CONFIGURED"})
                return
            try:
                body = self._body()
                self._json(200, route(body))
            except BadRequestError:
                self._json(400, {"error": "BAD_REQUEST"})
            except GarminError as e:
                self._json(e.http_status, {"error": e.code})
            except Exception:  # noqa: BLE001 - never echo an unexpected error's text
                self._json(500, {"error": "INTERNAL"})

        def _login(self, body: dict) -> dict:
            username, password = _strings(body, "username", "password")
            return auth.login(username, password).to_json()

        def _mfa(self, body: dict) -> dict:
            mfa_id, code = _strings(body, "mfaId", "code")
            return auth.resume(mfa_id, code.strip()).to_json()

        def _body(self) -> dict:
            try:
                length = int(self.headers.get("Content-Length") or "0")
            except ValueError:
                raise BadRequestError("length") from None
            if length <= 0 or length > MAX_BODY_BYTES:
                raise BadRequestError("length")
            try:
                body = json.loads(self.rfile.read(length))
            except ValueError:
                raise BadRequestError("json") from None
            if not isinstance(body, dict):
                raise BadRequestError("json")
            return body

        def _json(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def log_request(self, code: int | str = "-", size: int | str = "-") -> None:
            # ADR 0011 D9: method, path without the query string, status. Never headers,
            # bodies or query strings, which could carry credentials or tokens.
            print(f"{self.address_string()} {safe_request_summary(self.command, self.path)} {code}", flush=True)

        def log_message(self, format: str, *args) -> None:  # noqa: A002
            # The stdlib calls this for errors with the raw request line in args; log only
            # the safe summary instead.
            print(f"{self.address_string()} {safe_request_summary(self.command, self.path)} error", flush=True)

    return Handler


def main() -> None:
    # The library would otherwise read (and write) a token file named by this variable (ADR 0011 D1).
    os.environ.pop("GARMINTOKENS", None)
    secret = os.environ.get("GARMIN_SYNC_SECRET") or None
    if not secret:
        print("garmin-sync: GARMIN_SYNC_SECRET is not set; refusing every request except /health", flush=True)
    port = int(os.environ.get("PORT", "8090"))
    auth = GarminAuth(real_client_factory, mfa_store_from_env())
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(NotConfiguredAdapter(), auth, secret))
    print(f"garmin-sync {VERSION} listening on :{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
