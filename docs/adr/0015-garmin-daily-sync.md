# ADR 0015: Garmin daily sync: library, sign-in flow, what is fetched, the morning run

- Status: accepted (stage 1b-4, 2026-11-16; the coach agreed to the plan and to questions 1–3 the same day). Built in three PRs: **PR 1** (sign-in: Connect, MFA, Disconnect), **PR 2** (fetch and import, Sync now), **PR 3** (morning run, last-sync indicator, Today banner). Sections marked *(PR 2)* or *(PR 3)* describe the agreed design, implemented in that PR.
- Implements: ADR 0011 (credentials, unchanged except the amendment at its end), ADR 0010 (a re-fetched day is a revision).
- Does not change: `day_features`, `FEATURE_VERSION`, or how scores are computed (ADR 0008, frozen while the live holdout runs, ADR 0009 H8).

## In plain words
The app signs in to Garmin once with your password, keeps only an encrypted token, and then fetches each morning's data itself. That data goes into the same tables as your Garmin exports, through the same code, so scores and the Whoop comparison cannot tell a synced day from an exported one. If a sync fails, or Garmin wants you to sign in again, the screen says so, and exports keep working as before.

## Decisions

### D1. Library: `garminconnect` 0.3.20 alone; `garth` is no longer used (coach, question 1)
- `garth`, named in ADR 0011, announced in 2026 that it is deprecated and no longer maintained ("Garmin changed their auth"). `garminconnect` 0.3.x dropped it and signs in itself (Garmin's mobile sign-in, then access + refresh tokens that renew automatically).
- The sidecar pins `garminconnect==0.3.20` and every dependency (`garmin-sync/requirements.txt`). Updates are deliberate: one version at a time, with the tests. `tests/test_library_contract.py` fails if an update changes a part the sidecar relies on (the version, the MFA return, in-memory tokens, exception names).
- **No token file, ever.** The library normally saves tokens to `~/.garminconnect`. The sidecar never gives it a path: it reads tokens from the request body (`login(tokenstore=<JSON text>)`) and returns them with `client.dumps()`; `GARMINTOKENS` is removed from the environment at startup. A test checks that no token path is set. The throwaway folder proposed in the plan turned out to be unnecessary.

### D2. Sidecar endpoints (PR 1)
All except `GET /health` need `X-Sync-Secret` (ADR 0011 D4): wrong → 401 `BAD_SECRET`; secret unset → 503 `NOT_CONFIGURED`. Bodies are JSON, at most 64 KB.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /auth/login` | `username`, `password` | `{status: CONNECTED, tokens}` or `{status: MFA_REQUIRED, mfaId}` |
| `POST /auth/mfa` | `mfaId`, `code` | `{status: CONNECTED, tokens}` |
| `POST /sync/day` *(PR 2)* | `tokens`, `date` | the day's data and the (possibly refreshed) tokens |

Errors are fixed codes only, never the library's message or a Garmin response: `AUTH_FAILED` (401), `MFA_INVALID` (401), `MFA_EXPIRED` (410), `RATE_LIMITED` (429), `GARMIN_UNAVAILABLE` (502), `BAD_REQUEST` (400), `INTERNAL` (500). The library's own loggers are silenced (they can quote Garmin responses).

### D3. MFA retries (amends ADR 0011 D5)
ADR 0011 D5 deleted the waiting login on any failure. With the current library a mistyped code leaves Garmin's half-finished login usable, so the sidecar allows **3 tries** [configurable: `MFA_MAX_ATTEMPTS`; a mistyped code shouldn't force retyping the password, and more tries invite guessing and could get the account locked] within the **5 minutes** [configurable: `MFA_TTL_SECONDS`, ADR 0011 D5]. After the third try, on any other error, on success or on expiry, the waiting login is deleted. At most 10 logins wait at once (oldest dropped). The password and username are cleared from the waiting login before it is kept.

### D4. App endpoints and states (PR 1)
Coach only (`@PreAuthorize`, then AccessPolicy):
- `GET /api/athletes/{id}/garmin` → `state` (`NOT_CONFIGURED`, `NOT_CONNECTED`, `CONNECTED`, `RECONNECT_NEEDED`), `stored`, `connectedAt`, `tokenRefreshedAt`, `lastError` (a fixed code). A row locked with another key reads as `RECONNECT_NEEDED` / `KEY_CHANGED` (ADR 0011 D7).
- `POST …/garmin/connect` {username, password} and `POST …/garmin/mfa` {mfaId, code} → `{status, mfaId?}`. Errors carry a `code` property (`AUTH_FAILED`, `MFA_INVALID`, `MFA_EXPIRED`, `RATE_LIMITED`, `GARMIN_UNAVAILABLE`, `SIDECAR_UNREACHABLE`, `SECRET_MISMATCH`, `NOT_CONFIGURED`). A wrong Garmin password is **400**, not 401, so the browser does not mistake it for the end of the coach's own session.
- `DELETE …/garmin` → 204; works even while sync is not configured.
- A body that is not valid JSON is answered "Invalid request body" by the app's own handler, so Spring never logs the parser's message, which can quote part of the body (ADR 0011 D9).
- The Connect form is a card on the **Imports** page. It warns when the page is not opened on the Mac itself (ADR 0011 D1 network caveat).

### D5. What is fetched, and how it is stored *(PR 2)*
For each day: daily summary, sleep, HRV, the activity list with each activity's **original FIT file**, and the day's **all-day heart-rate (monitoring) FIT files**, the same files the Garmin export contains, not Garmin's coarser heart-rate summary. The app stores them as an import of kind `GARMIN_SYNC` through the same `RawWriter` as the export: same tables, same duplicate checks, same revision rule (ADR 0010), one import at a time per athlete. FIT files go through the existing FIT parsers, so heart rate and activities are identical by construction. Summary, sleep and HRV arrive with different field names than the export; a mapping turns them into the same raw rows, with a test that runs one synthetic day through both paths and requires identical rows. Verification on the coach's data: re-syncing days already imported from an export must add no revision rows (read-only SQL in PR 2).

### D6. Days fetched, retries *(PR 2)*
- Today plus the **previous 2 days** [configurable: covers a watch that syncs late and Garmin re-processing a night's sleep].
- Garmin unavailable or rate limited: retry after **10 and 30 minutes** [configurable: often enough to be ready by 08:00, rare enough not to get the account blocked], then the run is `FAILED` and the UI says so.
- Garmin rejects the token: no retry; `RECONNECT_NEEDED` (ADR 0011 D6).
- A run history table, `garmin_sync_run` (when, trigger, result, fixed error code, days, import batch).

### D7. After a sync: the same recompute as the button *(PR 2)*
After a sync that added rows, the app runs exactly what **Recompute features** runs (all features, then the ACTIVE model's scores). It takes about a minute on the coach's data (coach, 2026-11-16), and using the same code is the safest choice for the holdout. Each run is still written to `feature_recompute_log`, with `ran_by = 'garmin-sync'`.

### D8. Holdout markers (coach, question 3) *(PR 2)*
The Holdout tab marks Recompute features runs inside the window (ADR 0013 D6). A morning sync would add one every day, so runs with `ran_by = 'garmin-sync'` are labelled "sync" and left out of those markers. Only the coach's own runs stay marked. Display only: no score, rule or count changes.

### D9. The morning run *(PR 3)*
At **07:00 in the athlete's timezone** [configurable: an hour of slack before 08:00, the spec's deadline]. If the Mac was asleep at 07:00, the run starts as soon as it is awake again that day. The README gives the one Terminal command that wakes the Mac at 06:55 (coach, question 2). Plus **Sync now** on the Imports page. A "Last sync" line on Today and Imports; the Today banner tells apart "sync failed", "Garmin needs you to connect again" and "no data yet" (updates ADR 0014's wording).

## Consequences
- After PR 1 merges, the coach adds `GARMIN_TOKEN_KEY` and `GARMIN_SYNC_SECRET` to `.env` and connects (README section 8). Nothing is fetched until PR 2.
- A library update is a deliberate PR that changes `requirements.txt` and passes `test_library_contract.py`.
