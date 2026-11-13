# ADR 0006: Imports run in the background; uploads are deleted afterwards

- Status: accepted (Phase 0)

## Decision
- An upload is streamed to a work directory and hashed (SHA-256). It is recorded as a `PENDING` `import_batch` and processed on a background (virtual) thread. The API answers `202 Accepted`, and the UI polls `GET /api/imports/{id}`.
- Each batch runs in one database transaction. On failure it is marked `FAILED` with a short error, and nothing is written.
- Uploaded files are deleted when the batch finishes, success or failure. The database is the only copy of imported health data (see backups).
- Batches still `PENDING`/`RUNNING` at startup are marked `FAILED` ("interrupted by restart"). Re-running is safe because imports are idempotent.
- Re-uploading an identical file is allowed and only warns. The hash is not a dedup key, because the row-level keys already guarantee idempotency.
- Logs never contain payloads or credentials: errors are logged by type and message only.

## Addendum (after the first real import)
- **Stream writes** use multi-row `INSERT … VALUES (…),(…) ON CONFLICT DO NOTHING`, 2,000 rows per statement. Measured on 360k synthetic samples: 24.9 s → 9.2 s. PostgreSQL `COPY` through a staging table reached only 8.4 s, because the hypertable's index and conflict checks dominate; it was not worth the extra machinery. The JDBC driver's `reWriteBatchedInserts` was rejected because it hides per-row update counts, which the inserted/duplicate stats depend on.
- **Progress:** a running batch reports `{phase, done, total?}` in `import_batch.progress`. The phases are wellness, activities, fit and whoop. Each update is committed in its own `REQUIRES_NEW` transaction, at most every 2 s and on every phase change, so pollers see it while the import's single transaction is still open. The app also logs a "started" line, one line per phase, and one every 30 s.
- **No foreign keys from hypertables to `import_batch`** (fixed 2026-11-13, V9). The first real all-day-HR import deadlocked. Every new TimescaleDB chunk copies its table's foreign keys, and creating a key to `import_batch` locks that table until the import commits. The progress update, which writes `import_batch` in its own transaction, then waited on the import while the import waited on it. Progress updates now also use `lock_timeout = 1s` and are skipped on failure: progress is best effort and may never stall an import.
- **One import at a time per athlete.** A second upload while one is PENDING or RUNNING gets `409 Conflict`. Two imports of the same data would otherwise block each other on the same rows.
