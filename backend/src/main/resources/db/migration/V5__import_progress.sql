-- Live progress for running imports. Written in its own short transaction
-- (the import itself is one long transaction), so the UI can poll it.
ALTER TABLE import_batch
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN progress   JSONB;   -- {"phase": "fit", "done": 5200, "total": null}
