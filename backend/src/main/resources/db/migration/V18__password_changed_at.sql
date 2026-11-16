-- R-09: when a password last changed. A session that logged in before this moment is ended.
-- NULL = never changed since this column exists (existing sessions stay valid).
ALTER TABLE app_user ADD COLUMN password_changed_at TIMESTAMPTZ;
