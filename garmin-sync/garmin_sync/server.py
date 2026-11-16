"""Minimal HTTP server: GET /health. Standard library only."""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .adapter import GarminAdapter, NotConfiguredAdapter

VERSION = "0.1.0"


def safe_request_summary(method: str | None, path: str | None) -> str:
    """Method and path for a log line, with any query string or fragment removed."""
    clean = (path or "").split("?", 1)[0].split("#", 1)[0]
    return f"{method or '-'} {clean or '-'}"


def make_handler(adapter: GarminAdapter):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 (stdlib naming)
            if self.path == "/health":
                body = {
                    "status": "UP",
                    "version": VERSION,
                    "adapter": type(adapter).__name__,
                    "syncAvailable": not isinstance(adapter, NotConfiguredAdapter),
                }
                self._json(200, body)
            else:
                self._json(404, {"error": "not found"})

        def _json(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
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
    port = int(os.environ.get("PORT", "8090"))
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(NotConfiguredAdapter()))
    print(f"garmin-sync listening on :{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
