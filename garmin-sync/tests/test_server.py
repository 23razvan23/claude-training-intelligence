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
        NotConfiguredAdapter().fetch_day(date(2024, 1, 1))
