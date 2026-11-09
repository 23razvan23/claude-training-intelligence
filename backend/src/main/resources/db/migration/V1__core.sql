-- Core identity: athletes and the users who log in.
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE athlete (
    id                       BIGSERIAL PRIMARY KEY,
    name                     TEXT        NOT NULL,
    sex                      CHAR(1)     CHECK (sex IN ('M', 'F')),       -- for Banister TRIMP constants
    birth_year               INT,
    hr_max                   INT         CHECK (hr_max BETWEEN 100 AND 250),
    resting_hr               INT         CHECK (resting_hr BETWEEN 25 AND 120),
    lthr                     INT         CHECK (lthr BETWEEN 80 AND 230),
    threshold_pace_s_per_km  INT         CHECK (threshold_pace_s_per_km BETWEEN 120 AND 900),
    hr_zones                 JSONB,      -- [{"zone":1,"min":..,"max":..}, ...]
    pace_zones               JSONB,      -- [{"zone":"easy","minSPerKm":..,"maxSPerKm":..}, ...]
    timezone                 TEXT        NOT NULL DEFAULT 'Europe/Bucharest',
    injury_notes             TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id             BIGSERIAL PRIMARY KEY,
    username       TEXT        NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,
    role           TEXT        NOT NULL CHECK (role IN ('COACH', 'ATHLETE')),
    athlete_id     BIGINT      REFERENCES athlete (id) ON DELETE CASCADE,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT athlete_user_has_athlete CHECK (role = 'COACH' OR athlete_id IS NOT NULL)
);

CREATE UNIQUE INDEX app_user_one_login_per_athlete ON app_user (athlete_id) WHERE role = 'ATHLETE';
