ALTER TABLE learning_session ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
    CHECK (mode IN ('MANUAL','MIXED','REVIEW','NEW'));
ALTER TABLE learning_session ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE session_item ADD COLUMN item_kind VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
    CHECK (item_kind IN ('MANUAL','NEW','REVIEW','REPLAY'));
CREATE INDEX learning_session_resume ON learning_session(user_id,learning_date,created_at DESC);
