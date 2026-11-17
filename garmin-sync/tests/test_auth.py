"""Sign-in, MFA and the shared secret, against a fake Garmin client (no network, fake credentials)."""

import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from garmin_sync.adapter import NotConfiguredAdapter
from garmin_sync.auth import GarminAuth, GarminError, MfaStore, classify
from garmin_sync.server import make_handler, secret_matches

SECRET = "test-sync-secret"
FAKE_TOKENS = json.dumps({"di_token": "fake-access", "di_refresh_token": "fake-refresh", "di_client_id": "fake"})


# Named like the library's exceptions: classify() looks at the type name only.
class GarminConnectAuthenticationError(Exception):  # noqa: N818
    pass


class GarminConnectTooManyRequestsError(Exception):  # noqa: N818
    pass


class GarminConnectConnectionError(Exception):  # noqa: N818
    pass


class FakeInner:
    def dumps(self) -> str:
        return FAKE_TOKENS


class FakeGarmin:
    """Behaves like garminconnect.Garmin(return_on_mfa=True) for one scripted account."""

    instances: list["FakeGarmin"] = []

    def __init__(self, username, password, *, mfa=False, fail=None, good_code="123456"):
        self.username = username
        self.password = password
        self.client = FakeInner()
        self._mfa = mfa
        self._fail = fail
        self._good_code = good_code
        self.tokenstores = []
        FakeGarmin.instances.append(self)

    def login(self, tokenstore=None):
        self.tokenstores.append(tokenstore)
        if self._fail:
            raise self._fail
        if self.password != "test-password":
            raise GarminConnectAuthenticationError("401 Unauthorized for test-user / test-password")
        return ("needs_mfa", {"cookies": []}) if self._mfa else (None, None)

    def resume_login(self, client_state, mfa_code):
        if mfa_code != self._good_code:
            raise GarminConnectAuthenticationError("Widget MFA failed: bad code " + mfa_code)
        return None, None


def factory(**kw):
    return lambda u, p: FakeGarmin(u, p, **kw)


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


@pytest.fixture(autouse=True)
def _reset():
    FakeGarmin.instances.clear()


def serve(auth, secret=SECRET):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(NotConfiguredAdapter(), auth, secret))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, f"http://127.0.0.1:{srv.server_address[1]}"


def post(url, body, secret=SECRET):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    if secret is not None:
        req.add_header("X-Sync-Secret", secret)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


# --- GarminAuth -----------------------------------------------------------------


def test_login_returns_tokens_and_forgets_the_password():
    auth = GarminAuth(factory(), MfaStore(300, 3))
    result = auth.login("test-user", "test-password")
    assert result.status == "CONNECTED"
    assert result.tokens == FAKE_TOKENS
    g = FakeGarmin.instances[0]
    assert g.password is None and g.username is None
    assert g.tokenstores == [None]  # never a token file


def test_wrong_password_is_a_fixed_code_without_the_libraries_text():
    auth = GarminAuth(factory(), MfaStore(300, 3))
    with pytest.raises(GarminError) as e:
        auth.login("test-user", "wrong-password")
    assert e.value.code == "AUTH_FAILED"
    assert "wrong-password" not in str(e.value) and e.value.__cause__ is None
    assert FakeGarmin.instances[0].password is None


def test_mfa_two_steps_and_password_not_kept_while_waiting():
    auth = GarminAuth(factory(mfa=True), MfaStore(300, 3))
    first = auth.login("test-user", "test-password")
    assert first.status == "MFA_REQUIRED" and first.tokens is None and first.mfa_id
    assert FakeGarmin.instances[0].password is None
    done = auth.resume(first.mfa_id, "123456")
    assert done.status == "CONNECTED" and done.tokens == FAKE_TOKENS
    assert len(auth.mfa) == 0
    with pytest.raises(GarminError) as e:  # one-time id
        auth.resume(first.mfa_id, "123456")
    assert e.value.code == "MFA_EXPIRED"


def test_wrong_code_can_be_retried_until_the_limit():
    auth = GarminAuth(factory(mfa=True), MfaStore(300, 3))
    mfa_id = auth.login("test-user", "test-password").mfa_id
    for _ in range(2):
        with pytest.raises(GarminError) as e:
            auth.resume(mfa_id, "000000")
        assert e.value.code == "MFA_INVALID"
    assert auth.resume(mfa_id, "123456").status == "CONNECTED"

    mfa_id = auth.login("test-user", "test-password").mfa_id
    for _ in range(3):
        with pytest.raises(GarminError):
            auth.resume(mfa_id, "000000")
    with pytest.raises(GarminError) as e:  # used up after the 3rd try
        auth.resume(mfa_id, "123456")
    assert e.value.code == "MFA_EXPIRED"


def test_waiting_login_expires():
    clock = Clock()
    auth = GarminAuth(factory(mfa=True), MfaStore(300, 3, clock=clock))
    mfa_id = auth.login("test-user", "test-password").mfa_id
    clock.t += 301
    with pytest.raises(GarminError) as e:
        auth.resume(mfa_id, "123456")
    assert e.value.code == "MFA_EXPIRED"


def test_store_is_bounded():
    store = MfaStore(300, 3, max_pending=2)
    ids = [store.put(object()) for _ in range(3)]
    assert len(store) == 2
    with pytest.raises(GarminError):
        store.take(ids[0])


def test_classify():
    assert classify(GarminConnectTooManyRequestsError()).code == "RATE_LIMITED"
    assert classify(GarminConnectAuthenticationError()).code == "AUTH_FAILED"
    assert classify(GarminConnectConnectionError()).code == "GARMIN_UNAVAILABLE"
    assert classify(TimeoutError()).code == "GARMIN_UNAVAILABLE"


# --- HTTP -------------------------------------------------------------------------


def test_secret_comparison():
    assert secret_matches("abc", "abc")
    assert not secret_matches("abc", "abd")
    assert not secret_matches(None, "")
    assert not secret_matches("", "")
    assert not secret_matches("abc", None)


def test_login_over_http_needs_the_secret():
    srv, url = serve(GarminAuth(factory(), MfaStore(300, 3)))
    try:
        assert post(f"{url}/auth/login", {"username": "test-user", "password": "test-password"}, secret=None) == (
            401,
            {"error": "BAD_SECRET"},
        )
        assert (
            post(f"{url}/auth/login", {"username": "test-user", "password": "test-password"}, secret="nope")[0] == 401
        )
        assert FakeGarmin.instances == []  # nothing reached Garmin
        status, body = post(f"{url}/auth/login", {"username": "test-user", "password": "test-password"})
        assert status == 200 and body == {"status": "CONNECTED", "tokens": FAKE_TOKENS}
    finally:
        srv.shutdown()


def test_unset_secret_refuses_everything_but_health():
    srv, url = serve(GarminAuth(factory(), MfaStore(300, 3)), secret=None)
    try:
        assert post(f"{url}/auth/login", {"username": "u", "password": "p"}, secret="") == (
            503,
            {"error": "NOT_CONFIGURED"},
        )
        with urllib.request.urlopen(f"{url}/health") as r:
            assert json.load(r)["secretConfigured"] is False
    finally:
        srv.shutdown()


def test_mfa_over_http_and_errors_are_codes_only(capsys):
    srv, url = serve(GarminAuth(factory(mfa=True), MfaStore(300, 3)))
    try:
        status, body = post(f"{url}/auth/login", {"username": "test-user", "password": "test-password"})
        assert status == 200 and body["status"] == "MFA_REQUIRED" and "tokens" not in body
        assert post(f"{url}/auth/mfa", {"mfaId": body["mfaId"], "code": "999999"}) == (401, {"error": "MFA_INVALID"})
        status, done = post(f"{url}/auth/mfa", {"mfaId": body["mfaId"], "code": " 123456 "})
        assert status == 200 and done["tokens"] == FAKE_TOKENS
        assert post(f"{url}/auth/mfa", {"mfaId": "unknown", "code": "1"}) == (410, {"error": "MFA_EXPIRED"})
        assert post(f"{url}/auth/login", {"username": "test-user"}) == (400, {"error": "BAD_REQUEST"})
        assert post(f"{url}/auth/login", {"username": "test-user", "password": "wrong-pass"}) == (
            401,
            {"error": "AUTH_FAILED"},
        )
    finally:
        srv.shutdown()
    out = capsys.readouterr().out
    for secret in (
        "test-password",
        "wrong-pass",
        "999999",
        "123456",
        "fake-access",
        "fake-refresh",
        SECRET,
        "test-user",
    ):
        assert secret not in out
    assert "POST /auth/login 200" in out


def test_garmin_failures_map_to_status_codes():
    for exc, expected in (
        (GarminConnectTooManyRequestsError(), (429, "RATE_LIMITED")),
        (GarminConnectConnectionError("boom https://sso.garmin.com/?x=secret"), (502, "GARMIN_UNAVAILABLE")),
    ):
        srv, url = serve(GarminAuth(factory(fail=exc), MfaStore(300, 3)))
        try:
            status, body = post(f"{url}/auth/login", {"username": "test-user", "password": "test-password"})
            assert (status, body["error"]) == expected and len(body) == 1
        finally:
            srv.shutdown()


def test_oversized_or_invalid_body_is_refused():
    srv, url = serve(GarminAuth(factory(), MfaStore(300, 3)))
    try:
        req = urllib.request.Request(f"{url}/auth/login", data=b"not json", method="POST")
        req.add_header("X-Sync-Secret", SECRET)
        with pytest.raises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req)
        assert e.value.code == 400
        status, _ = post(f"{url}/auth/login", {"username": "u", "password": "x" * 70000})
        assert status == 400
    finally:
        srv.shutdown()
