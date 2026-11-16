-- Garmin daily sync credentials (stage 1b-4, ADR 0011 D3). Tokens only: never a password,
-- username or e-mail. The token bundle is encrypted by the app with AES-256-GCM
-- (GARMIN_TOKEN_KEY, which only the app has); this table holds ciphertext only.
-- One row per athlete; no row = not connected. Disconnect deletes the row.
CREATE TABLE garmin_credential (
    athlete_id         BIGINT      PRIMARY KEY REFERENCES athlete(id) ON DELETE CASCADE,
    token_ciphertext   BYTEA       NOT NULL,   -- nonce || ciphertext || tag (ADR 0011 D2)
    key_id             TEXT        NOT NULL,   -- first 8 hex chars of SHA-256 of the key (D2, D7)
    format_version     SMALLINT    NOT NULL DEFAULT 1,
    status             TEXT        NOT NULL CHECK (status IN ('CONNECTED', 'RECONNECT_NEEDED')),
    connected_at       TIMESTAMPTZ NOT NULL,
    token_refreshed_at TIMESTAMPTZ,
    last_error         TEXT,                   -- a fixed code (D9), never a Garmin payload
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
