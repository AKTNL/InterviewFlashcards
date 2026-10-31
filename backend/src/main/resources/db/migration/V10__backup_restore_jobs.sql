-- AWAITING_CONFIRMATION needs 21 characters; the foundation column was only 20.
ALTER TABLE restore_job ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE backup_job ADD COLUMN operation_id UUID;
ALTER TABLE backup_job ADD COLUMN manifest JSONB;
ALTER TABLE backup_job ADD COLUMN progress INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backup_job ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE UNIQUE INDEX backup_operation ON backup_job(user_id,operation_id);
ALTER TABLE restore_job ADD COLUMN operation_id UUID;
ALTER TABLE restore_job ADD COLUMN request_digest VARCHAR(64);
ALTER TABLE restore_job ADD COLUMN expected_epoch UUID;
ALTER TABLE restore_job ADD COLUMN expected_revision BIGINT;
ALTER TABLE restore_job ADD COLUMN progress INTEGER NOT NULL DEFAULT 0;
ALTER TABLE restore_job ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE UNIQUE INDEX restore_operation ON restore_job(user_id,operation_id);
