-- The first real all-day-HR import deadlocked. Each new TimescaleDB chunk of
-- monitoring_hr copies the table's foreign key to import_batch, and creating that
-- key locks import_batch (SHARE ROW EXCLUSIVE) until the import commits. The
-- progress update, a separate transaction writing import_batch, then waited on
-- the import, which waited on the progress update. import_batch_id stays as a
-- plain column for traceability. Dropping the key on the hypertable also drops
-- it from existing chunks.
ALTER TABLE monitoring_hr DROP CONSTRAINT IF EXISTS monitoring_hr_import_batch_id_fkey;
