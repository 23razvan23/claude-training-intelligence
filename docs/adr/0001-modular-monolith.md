# ADR 0001: Modular monolith in Java, Python only for offline calibration

- Status: accepted (Phase 0)
- Source: SPEC.md, "Architecture and tech stack"

## Context
One coach, a handful of athletes, one server. The daily pipeline (sync → normalize → metrics → adaptive engine) must be reproducible and easy to reason about. Model calibration is batch work that benefits from pandas, scikit-learn and statsmodels.

## Decision
- One Spring Boot 3 app (Java 21, Gradle), one package per domain: `athletes`, `ingestion`, `metrics`, `planning`, `adaptation`, plus `security` and `common`. Packages talk through plain Java services, not HTTP.
- Data access uses `JdbcClient` and explicit SQL rather than JPA. The raw tables rely on `ON CONFLICT DO NOTHING`, views and TimescaleDB features that an ORM would hide.
- PostgreSQL 16 + TimescaleDB, schema owned by Flyway. `activity_stream` is a hypertable.
- Python lives in two places only: `calibration/` (offline, Phase 1) and the `garmin-sync/` sidecar (an isolated unofficial-API adapter). Daily code reads model coefficients as versioned JSON.
- React + TypeScript SPA served by nginx, which proxies `/api` so the session cookie stays same-origin.

## Consequences
- A single deployable plus two small sidecars; a module split later is possible because packages do not share tables casually.
- No ORM means more hand-written SQL, covered by integration tests on a real TimescaleDB (Testcontainers).
