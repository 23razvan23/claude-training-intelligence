"""The parts of the pinned garminconnect the sidecar relies on (no network).

If an upgrade changes any of these, this test fails before the coach's morning sync does.
"""

import inspect
import json

import garminconnect

from garmin_sync.auth import real_client_factory


def test_pinned_version():
    import importlib.metadata

    assert importlib.metadata.version("garminconnect") == "0.3.20"


def test_factory_returns_on_mfa_and_takes_no_token_file():
    g = real_client_factory("test-user", "test-password")
    assert g.return_on_mfa is True
    assert "tokenstore" in inspect.signature(g.login).parameters
    assert list(inspect.signature(g.resume_login).parameters) == ["client_state", "mfa_code"]


def test_tokens_round_trip_in_memory():
    g = real_client_factory(None, None)
    fake = {"di_token": "fake.header.sig", "di_refresh_token": "fake-refresh", "di_client_id": "fake-client"}
    g.client.loads(json.dumps(fake))
    assert json.loads(g.client.dumps()) == fake
    assert g.client._tokenstore_path is None  # nothing is ever written to disk


def test_exception_names_used_by_classify():
    for name in (
        "GarminConnectAuthenticationError",
        "GarminConnectTooManyRequestsError",
        "GarminConnectConnectionError",
    ):
        assert hasattr(garminconnect, name)


def test_fetch_methods_the_adapter_calls():
    from garmin_sync.adapter import _original_format

    g = real_client_factory(None, None)
    for name in (
        "get_user_summary",
        "get_sleep_data",
        "get_hrv_data",
        "download",
        "get_activities_by_date",
        "download_activity",
    ):
        assert callable(getattr(g, name)), name
    assert _original_format(g) is garminconnect.Garmin.ActivityDownloadFormat.ORIGINAL
    assert hasattr(garminconnect, "GarminConnectNotFoundError")
