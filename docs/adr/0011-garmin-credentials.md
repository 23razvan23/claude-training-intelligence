# ADR 0011: Garmin credentials: tokens only, encrypted by the app, a stateless sidecar

- Status: accepted (review item R-11, 2026-11-16). Design only: the code comes with stage 1b-4.
- Coach's decision: **option (a)**, given 2026-11-16 (below, D1).
- Related: ADR 0005 (auth), ADR 0006 (imports, logs), ADR 0010 (raw revisions: how a re-fetched day replaces a partial one), SPEC.md "Privacy" and "Resilience".

## In plain words
To sync every morning, the app has to sign in to Garmin Connect as you. You type your Garmin password **once**, in the app. The app uses it to sign in a single time and then forgets it. What it keeps is the renewable "access token" Garmin hands back, locked with a key that only lives in your `.env` file. If the key is missing, the app still works, just without sync. If Garmin stops accepting the token, the app tells you and asks you to connect again. **Disconnect** deletes the token. Backups contain only the locked version, which is useless without the key, so keep the key away from the backup copies.

## Decisions

### D1. Tokens only; the password is never stored (the coach's choice)
The options put to the coach (REVIEW.md R-11, §5 question 6):
- **(a) Chosen.** The coach types the Garmin password once; the app keeps only a renewable access token, encrypted at rest. A Disconnect button removes it.
- (b) Store the password, encrypted. Rejected: a leak would expose the password itself (often reused elsewhere), not just a revocable token, and it would let the app sign in again silently forever.
- (c) No automatic sync; keep uploading exports. Rejected as the main route, but kept as the fallback (D11).

The password travels **browser → app → sidecar** in request bodies (never in a URL), is used for one login by `garth`, and is then dropped. It is never written to the database, a file, a log, an error message, a stack trace or an HTTP response. The sidecar calls `garth` so that it does **not** save tokens to its home directory (no `garth.save`/`dump`, no `~/.garth`).

**Network caveat (coach accepted, 2026-11-16):** the app has no HTTPS yet, so the password crosses the local network unencrypted once, at Connect time. Connect from the Mac itself (`http://localhost:<WEB_PORT>`) or over a network you trust. HTTPS stays on the backlog (PLAN.md §5, "TLS/`COOKIE_SECURE` guidance").

### D2. Encryption: AES-256-GCM, key from `.env`, held by the Java app only
- Key: `GARMIN_TOKEN_KEY`, **32 random bytes, base64-encoded** (44 characters). Generate with `openssl rand -base64 32`. Documented in `.env.example`.
- Only the **app** container receives the key. The sidecar, the database and the backup container never see it.
- Cipher: AES-256-GCM (JDK `javax.crypto`, no extra library). A fresh **random 12-byte nonce** for every encryption; 128-bit authentication tag. Stored value = `nonce ‖ ciphertext ‖ tag`.
- Additional authenticated data (AAD) = `"garmin-token:v1:athlete:<athlete_id>"`, so a ciphertext copied to another athlete's row fails to decrypt.
- What is encrypted: the token bundle exactly as the sidecar returns it (JSON of `garth`'s OAuth1 and OAuth2 tokens), treated by Java as an opaque blob.
- A key identifier `key_id` = first 8 hex characters of SHA-256 of the key is stored next to the ciphertext. It is not secret enough to help an attacker and lets the app tell "different key" (D7) from "corrupted row".

### D3. Storage: a new table `garmin_credential`, migration with stage 1b-4
Designed here; the Flyway migration is written **in stage 1b-4** (the next free version number then; V19 at the time of writing), because nothing writes to the table before sync exists.

```sql
CREATE TABLE garmin_credential (
    athlete_id        BIGINT PRIMARY KEY REFERENCES athlete(id) ON DELETE CASCADE,
    token_ciphertext  BYTEA       NOT NULL,   -- nonce ‖ ciphertext ‖ tag (D2)
    key_id            TEXT        NOT NULL,   -- which key encrypted it (D2, D7)
    format_version    SMALLINT    NOT NULL DEFAULT 1,
    status            TEXT        NOT NULL CHECK (status IN ('CONNECTED', 'RECONNECT_NEEDED')),
    connected_at      TIMESTAMPTZ NOT NULL,
    token_refreshed_at TIMESTAMPTZ,
    last_error        TEXT,                   -- short, fixed message (D9); never a Garmin payload
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
- One row per athlete; no row = not connected. No Garmin username, e-mail or password column.
- Coach-only endpoints (stage 1b-4): connect, MFA code, status, disconnect. Status returns only `status`, `connected_at`, `token_refreshed_at`, `last_error`; the ciphertext never leaves the app.
- Sync run history (last sync, result) belongs to 1b-4's own design, not this table.

### D4. The sidecar: stateless, internal, shared secret
- **Stateless:** the sidecar keeps no token between requests (except the short MFA wait, D5). Every sync request from the app carries the decrypted token bundle in the **JSON body**. If `garth` refreshes the OAuth2 token during the call, the response returns the new bundle, and the app re-encrypts and stores it (`token_refreshed_at`).
- **Internal only:** it stays on the Compose network with `expose`, never `ports`; nothing on the Mac or the LAN can reach it.
- **Shared secret:** every request except `GET /health` must carry `X-Sync-Secret: <GARMIN_SYNC_SECRET>`, compared in constant time (`hmac.compare_digest`); otherwise `401` and nothing else happens. `GARMIN_SYNC_SECRET` is a second `.env` value (`openssl rand -base64 32`), given to the app and the sidecar only. This stops another container on the network (or a mistake that publishes the port) from using the sidecar to reach Garmin. If the secret is unset, the sidecar refuses every request except `/health`.
- Both new variables enter `docker-compose.yml` in stage 1b-4, written as `${GARMIN_TOKEN_KEY:-}` / `${GARMIN_SYNC_SECRET:-}` so compose keeps parsing when they are empty (the app starts, D10).
- Transport between app and sidecar is plain HTTP on the internal network; acceptable because it never leaves the Docker host.

### D5. Multi-factor login (MFA)
- The Connect form has username and password. If Garmin asks for a code, the app's response says `MFA_REQUIRED` and the **same form** shows a code field; the coach types the code and submits.
- `garth` supports this as a two-step login (it returns a client state to resume with the code). The sidecar holds that state **in memory only**, under a random one-time id, for at most 5 minutes [configurable: long enough to read an e-mail or SMS code, short enough not to leave a half-login lying around], and deletes it on use, on failure or on expiry. The app passes the id back with the code. A sidecar restart simply means "start the Connect again".
- The password is not kept for the MFA step.

### D6. Token refresh, expiry and rejection
- `garth` keeps a long-lived OAuth1 token and a short-lived OAuth2 token, exchanging the first for a new second one when needed. Garmin does not document the lifetimes; the design does not depend on them.
- **Refreshed** bundle → re-encrypted and stored (D4).
- **Rejected** (Garmin answers 401/403 to the token or the exchange) → the sidecar returns `AUTH_REJECTED`; the app sets `status = 'RECONNECT_NEEDED'`, stops syncing for that athlete (no retries, which could get the account blocked) and the UI shows "Garmin needs you to connect again" with the Connect form. Manual imports keep working.
- **Garmin unreachable, 5xx or rate limited** → not an auth problem: retry with backoff as stage 1b-4 specifies; the token stays.

### D7. Disconnect and key rotation
- **Disconnect** deletes the row. The token cannot be revoked on Garmin's side through this API; the coach can additionally sign out of devices in Garmin Connect's account settings or change the Garmin password (both described in the README in 1b-4).
- **Key rotation = reconnect.** To change the key: put a new `GARMIN_TOKEN_KEY` in `.env`, restart, connect again. A row whose `key_id` differs from the current key's, or that fails to decrypt, is treated as `RECONNECT_NEEDED` and replaced on the next Connect. There is no re-encryption tool; one Connect is cheaper and safer than code that holds two keys.
- Losing `.env` costs one reconnect, no data.

### D8. What backups contain
- The nightly `pg_dump` contains the `garmin_credential` row: **ciphertext only**, then encrypted again with `BACKUP_PASSPHRASE`.
- **The key must not be stored with the backups.** `GARMIN_TOKEN_KEY` lives in `.env`, which is not in `./backups`. When the coach copies `backups/` to another disk or cloud drive (README), `.env` must not go with it. Keep a copy of `.env` (or just the passphrase) somewhere else, like the backup passphrase.
- A restored backup on a machine with a different key needs a reconnect (D7).

### D9. What must never be logged or put in a URL
Never in logs, URLs, query strings, error messages, exception texts, HTTP responses (except the sidecar's token response to the app) or test fixtures:
- the Garmin password, the MFA code, the token bundle (plain or encrypted), `GARMIN_TOKEN_KEY`, `GARMIN_SYNC_SECRET`, the Garmin username, and raw Garmin responses.
- **Secrets travel only in request bodies or the `X-Sync-Secret` header**, never in paths or query strings.
- The sidecar logs one line per request: client, method, **path without the query string**, status (done in this PR: `garmin_sync/server.py`, tested). It does not log headers or bodies.
- The app logs sync errors by kind and a fixed message (`AUTH_REJECTED`, `GARMIN_UNAVAILABLE`, …), as ADR 0006 already requires for imports. `last_error` holds the same fixed message.
- Java request DTOs holding the password, code or tokens override `toString()` to hide them; the Spring request log is not enabled for these endpoints.
- Tests use obviously fake values (`"test-password"`, a key from `SecureRandom`), never real ones.

### D10. Startup check: sync refuses, the app starts
At startup the app reads `GARMIN_TOKEN_KEY` once:
- missing or empty, not valid base64, or not exactly 32 bytes after decoding → logs one warning ("Garmin sync disabled: GARMIN_TOKEN_KEY missing or not 32 bytes; see .env.example") without printing the value, and **sync is disabled**: Connect, Sync now and the morning run answer "Garmin sync is not configured" (HTTP 503) and the UI says so with the fix. Everything else (imports, scores, holdout) works as before.
- `GARMIN_SYNC_SECRET` missing → same behaviour (sync disabled, app starts).
- The check is a pure function with unit tests for each case (missing, short, long, not base64, valid).

### D11. Unofficial API risk and fallback
`garminconnect`/`garth` use Garmin's private sign-in and web endpoints, not an official API. Garmin can change them or block them at any time, and a library update may be needed (pin versions; update deliberately). When that happens sync fails with a clear message, the "last sync" indicator goes stale, and the coach falls back to what works today: **Garmin account exports** (and FIT uploads) through the Imports page. Because a re-fetched day is a new revision (ADR 0010), mixing synced days and exported days is safe in either order.

## Consequences
- Stage 1b-4 implements this ADR: the migration (D3), the crypto and startup check (D2, D10), the sidecar endpoints with the shared secret (D4) and MFA (D5), refresh/rejection handling (D6), Disconnect (D7), the compose variables (D4), and README notes (D1 network caveat, D7, D8). Its prompt in `docs/PROMPTS.md` points here.
- After 1b-4 merges, the coach adds `GARMIN_TOKEN_KEY` and `GARMIN_SYNC_SECRET` to `.env` (commands in `.env.example`) before connecting.
- Not decided here: the Garmin library versions, the sync schedule and what is fetched (1b-4).

## Amendment (stage 1b-4, 2026-11-16; coach agreed, question 1 of the 1b-4 plan)
- **Library:** `garth` is deprecated and no longer maintained. The sidecar uses `garminconnect==0.3.20` alone, which signs in itself and returns an access + refresh token pair instead of `garth`'s OAuth1/OAuth2 pair. Everything above still holds with "the token bundle" meaning that pair: opaque to Java, encrypted with AES-256-GCM, refreshed bundles re-encrypted (D4, D6), rejection → `RECONNECT_NEEDED` (D6).
- **No token file (D1):** the sidecar passes tokens to the library as JSON text and reads them back in memory; it never gives the library a file path, and removes `GARMINTOKENS` from its environment at startup.
- **MFA (D5):** a mistyped code may be retried, up to 3 tries within the 5 minutes; then, or on success, expiry or any other error, the waiting login is deleted. The password is cleared before the waiting login is kept. Details: ADR 0015 D3.
