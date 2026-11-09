# ADR 0006: Imports run in the background; uploads are deleted afterwards

- Status: accepted (Phase 0)

## Decision
- An upload is streamed to a work directory and hashed (SHA-256). It is recorded as a `PENDING` `import_batch` and processed on a background (virtual) thread. The API answers `202 Accepted`, and the UI polls `GET /api/imports/{id}`.
- Each batch runs in one database transaction. On failure it is marked `FAILED` with a short error, and nothing is written.
- Uploaded files are deleted when the batch finishes, success or failure. The database is the only copy of imported health data (see backups).
- Batches still `PENDING`/`RUNNING` at startup are marked `FAILED` ("interrupted by restart"). Re-running is safe because imports are idempotent.
- Re-uploading an identical file is allowed and only warns. The hash is not a dedup key, because the row-level keys already guarantee idempotency.
- Logs never contain payloads or credentials: errors are logged by type and message only.
