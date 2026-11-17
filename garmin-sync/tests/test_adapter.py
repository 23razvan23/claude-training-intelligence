"""Fetching days through a fake garminconnect client (no network, fake tokens, synthetic payloads)."""

import base64
import json
import threading
import urllib.error
import urllib.request
from datetime import date
from http.server import ThreadingHTTPServer

import pytest

from garmin_sync.adapter import WELLNESS_FIT_PATH, GarminConnectAdapter
from garmin_sync.auth import GarminAuth, GarminError, MfaStore
from garmin_sync.server import make_handler

SECRET = "test-sync-secret"
TOKENS_IN = json.dumps({"di_token": "fake-old", "di_refresh_token": "fake-refresh", "di_client_id": "fake"})
TOKENS_OUT = json.dumps({"di_token": "fake-new", "di_refresh_token": "fake-refresh", "di_client_id": "fake"})


class GarminConnectAuthenticationError(Exception):  # noqa: N818
    pass


class GarminConnectNotFoundError(Exception):  # noqa: N818
    pass


class GarminConnectConnectionError(Exception):  # noqa: N818
    pass


class GarminConnectTooManyRequestsError(Exception):  # noqa: N818
    pass


class Inner:
    def __init__(self):
        self.tokens = None

    def dumps(self):
        return TOKENS_OUT if self.tokens else None


class FakeGarmin:
    class ActivityDownloadFormat:
        ORIGINAL = "ORIGINAL"

    def __init__(self, fail_login=None, fail_on=None, no_data=()):
        self.client = Inner()
        self.username = None
        self.password = None
        self.fail_login = fail_login
        self.fail_on = fail_on or {}
        self.no_data = set(no_data)
        self.calls = []

    def login(self, tokenstore=None):
        self.calls.append(("login", tokenstore))
        if self.fail_login:
            raise self.fail_login
        self.client.tokens = tokenstore
        return None, None

    def _maybe(self, label, value):
        self.calls.append(label)
        if label in self.fail_on:
            raise self.fail_on[label]
        if label in self.no_data:
            raise GarminConnectNotFoundError("404 for " + label)
        return value

    def get_user_summary(self, d):
        return self._maybe(f"summary:{d}", {"calendarDate": d, "restingHeartRate": 47, "bodyBatteryHighestValue": 88})

    def get_sleep_data(self, d):
        return self._maybe(
            f"sleep:{d}", {"dailySleepDTO": {"calendarDate": d, "deepSleepSeconds": 3600}, "sleepLevels": [1] * 500}
        )

    def get_hrv_data(self, d):
        return self._maybe(
            f"hrv:{d}", {"hrvSummary": {"calendarDate": d, "lastNightAvg": 52}, "hrvReadings": [1] * 100}
        )

    def download(self, path):
        return self._maybe(path, b"PK-fake-wellness-zip")

    def get_activities_by_date(self, start, end):
        return self._maybe(
            "activities", [{"activityId": 111, "startTimeGMT": f"{start} 06:00:00"}, {"activityName": "no id"}]
        )

    def download_activity(self, aid, fmt):
        assert fmt == "ORIGINAL"
        return self._maybe(f"activityFit:{aid}", b"PK-fake-activity-zip")


def adapter_with(fake):
    return GarminConnectAdapter(lambda u, p: fake)


DAYS = [date(2026, 11, 15), date(2026, 11, 16)]


def test_fetches_each_day_and_returns_refreshed_tokens():
    fake = FakeGarmin()
    out = adapter_with(fake).fetch_days(TOKENS_IN, DAYS)
    assert fake.calls[0] == ("login", TOKENS_IN)  # tokens in memory, never a path
    assert out["tokens"] == TOKENS_OUT
    assert [d["date"] for d in out["days"]] == ["2026-11-15", "2026-11-16"]
    day = out["days"][0]
    assert day["summary"]["restingHeartRate"] == 47
    assert day["sleep"] == {"calendarDate": "2026-11-15", "deepSleepSeconds": 3600}  # trimmed to the summary
    assert day["hrv"] == {"calendarDate": "2026-11-15", "lastNightAvg": 52}
    assert base64.b64decode(day["wellnessFit"]) == b"PK-fake-wellness-zip"
    assert WELLNESS_FIT_PATH.format(day="2026-11-16") in fake.calls
    assert len(out["activities"]) == 1  # the entry without an id is ignored
    assert base64.b64decode(out["activities"][0]["fit"]) == b"PK-fake-activity-zip"
    assert out["missing"] == []


def test_missing_parts_are_named_not_fatal():
    fake = FakeGarmin(no_data={"hrv:2026-11-16", "activityFit:111", WELLNESS_FIT_PATH.format(day="2026-11-15")})
    out = adapter_with(fake).fetch_days(TOKENS_IN, DAYS)
    assert out["days"][1]["hrv"] is None
    assert out["days"][0]["wellnessFit"] is None
    assert out["activities"][0]["fit"] is None
    assert sorted(out["missing"]) == ["activityFit:111", "hrv:2026-11-16", "wellnessFit:2026-11-15"]


def test_rejected_token_is_auth_rejected():
    with pytest.raises(GarminError) as e:
        adapter_with(FakeGarmin(fail_login=GarminConnectAuthenticationError("401 token fake-old"))).fetch_days(
            TOKENS_IN, DAYS
        )
    assert e.value.code == "AUTH_REJECTED"
    with pytest.raises(GarminError) as e:
        fake = FakeGarmin(fail_on={"sleep:2026-11-15": GarminConnectAuthenticationError("401")})
        adapter_with(fake).fetch_days(TOKENS_IN, DAYS)
    assert e.value.code == "AUTH_REJECTED"


def test_outage_or_rate_limit_stops_the_whole_fetch():
    fake = FakeGarmin(fail_on={"summary:2026-11-16": GarminConnectConnectionError("500 https://x?y=fake-old")})
    with pytest.raises(GarminError) as e:
        adapter_with(fake).fetch_days(TOKENS_IN, DAYS)
    assert e.value.code == "GARMIN_UNAVAILABLE" and "fake-old" not in str(e.value)
    with pytest.raises(GarminError) as e:
        adapter_with(FakeGarmin(fail_on={"activities": GarminConnectTooManyRequestsError()})).fetch_days(
            TOKENS_IN, DAYS
        )
    assert e.value.code == "RATE_LIMITED"


def serve(adapter):
    auth = GarminAuth(lambda u, p: None, MfaStore(300, 3))
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(adapter, auth, SECRET))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, f"http://127.0.0.1:{srv.server_address[1]}"


def post(url, body, secret=SECRET):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("X-Sync-Secret", secret)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def test_sync_days_over_http(capsys):
    srv, url = serve(adapter_with(FakeGarmin()))
    try:
        status, body = post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": ["2026-11-16", "2026-11-15"]})
        assert status == 200 and body["tokens"] == TOKENS_OUT and len(body["days"]) == 2
        assert body["days"][0]["date"] == "2026-11-15"  # sorted
        assert post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": []})[0] == 400
        assert post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": ["2026-13-01"]})[0] == 400
        assert post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": ["2026-11-15", "2026-11-15"]})[0] == 400
        too_many = [f"2026-10-{d:02d}" for d in range(1, 32)] + ["2026-11-01"]
        assert post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": too_many})[0] == 400
        assert post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": ["2026-11-15"]}, secret="nope")[0] == 401
    finally:
        srv.shutdown()
    out = capsys.readouterr().out
    assert "fake-old" not in out and "fake-new" not in out and "POST /sync/days 200" in out


def test_sync_days_auth_rejected_over_http():
    srv, url = serve(adapter_with(FakeGarmin(fail_login=GarminConnectAuthenticationError("x"))))
    try:
        assert post(f"{url}/sync/days", {"tokens": TOKENS_IN, "dates": ["2026-11-15"]}) == (
            401,
            {"error": "AUTH_REJECTED"},
        )
    finally:
        srv.shutdown()
