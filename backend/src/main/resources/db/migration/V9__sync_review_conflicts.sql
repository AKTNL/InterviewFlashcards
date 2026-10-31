ALTER TABLE sync_conflict ADD COLUMN data_epoch UUID;
ALTER TABLE sync_conflict ADD COLUMN operation_id UUID;
ALTER TABLE sync_conflict ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE sync_conflict ADD COLUMN resolution VARCHAR(20) CHECK (resolution IN ('CURRENT','CANDIDATE'));
CREATE UNIQUE INDEX sync_conflict_operation ON sync_conflict(user_id,data_epoch,operation_id);
CREATE INDEX sync_conflict_pending ON sync_conflict(user_id) WHERE NOT resolved;
ALTER TABLE review_event DROP CONSTRAINT review_event_kind_check;
ALTER TABLE review_event ADD CONSTRAINT review_event_kind_check CHECK (kind IN ('RATE','UNDO','RELEARN','CORRECT'));
