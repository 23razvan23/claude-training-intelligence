-- At most one ACTIVE model version per athlete: the one daily scoring (Phase 1b)
-- and the live comparison with Whoop use.
CREATE UNIQUE INDEX model_version_one_active ON model_version (athlete_id) WHERE status = 'ACTIVE';
