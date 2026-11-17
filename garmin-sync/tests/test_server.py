import json
import threading
import urllib.error
import urllib.request
from datetime import date
from http.server import ThreadingHTTPServer

import pytest

from garmin_sync.adapter import NotConfiguredAdapter, SyncUnavailableError
from garmin_sync.server import make_handler


@pytest.fixture
def server():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(NotConfiguredAdapter()))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def test_health(server):
    with urllib.request.urlopen(f"{server}/health") as r:
        body = json.load(r)
    assert body["status"] == "UP"
    assert body["syncAvailable"] is False


def test_unknown_path_is_404(server):
    with pytest.raises(urllib.error.HTTPError) as e:
        urllib.request.urlopen(f"{server}/nope")
    assert e.value.code == 404


def test_stub_adapter_refuses():
    with pytest.raises(SyncUnavailableError):
        NotConfiguredAdapter().fetch_days("{}", [date(2024, 1, 1)])


def test_log_lines_never_contain_query_strings(server, capsys):
    # ADR 0011 D9: a secret placed in a URL by mistake must not reach the logs.
    with pytest.raises(urllib.error.HTTPError):
        urllib.request.urlopen(f"{server}/nope?token=fake-secret-value#frag")
    with pytest.raises(urllib.error.HTTPError):
        urllib.request.urlopen(f"{server}/health?password=fake-password")
    with urllib.request.urlopen(f"{server}/health") as r:
        r.read()
    out = capsys.readouterr().out
    assert "fake-secret-value" not in out
    assert "fake-password" not in out
    assert "GET /nope 404" in out
    assert "GET /health 200" in out


def test_malformed_request_logs_no_raw_line(server, capsys):
    import socket

    host, port = server.removeprefix("http://").split(":")
    with socket.create_connection((host, int(port))) as s:
        s.sendall(b"BOGUS /x?token=fake-secret-value HTTP/9.9\r\n\r\n")
        s.recv(1024)
    out = capsys.readouterr().out
    assert "fake-secret-value" not in out


def test_safe_request_summary():
    from garmin_sync.server import safe_request_summary

    assert safe_request_summary("POST", "/sync?x=1") == "POST /sync"
    assert safe_request_summary(None, None) == "- -"
