"""Minimal HTTP server: GET /health. Standard library only."""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .adapter import GarminAdapter, NotConfiguredAdapter

VERSION = "0.1.0"


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

        def log_message(self, format: str, *args) -> None:  # noqa: A002
            # Request lines only; never payloads or credentials.
            print(f"{self.address_string()} {format % args}", flush=True)

    return Handler


def main() -> None:
    port = int(os.environ.get("PORT", "8090"))
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(NotConfiguredAdapter()))
    print(f"garmin-sync listening on :{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
