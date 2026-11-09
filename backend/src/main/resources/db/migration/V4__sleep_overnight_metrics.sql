-- Overnight metrics that both Garmin (sleepData.json) and Whoop (sleeps.csv)
-- report per sleep. Respiration and SpO2 during sleep are recovery features.
ALTER TABLE sleep_session
    ADD COLUMN avg_respiration  NUMERIC(4, 1),
    ADD COLUMN avg_sleep_stress NUMERIC(5, 2),
    ADD COLUMN avg_spo2_pct     NUMERIC(4, 1),
    ADD COLUMN lowest_spo2_pct  NUMERIC(4, 1);
