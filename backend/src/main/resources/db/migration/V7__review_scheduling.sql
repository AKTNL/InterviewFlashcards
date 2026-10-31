ALTER TABLE review_state DROP CONSTRAINT review_state_stage_check;
ALTER TABLE review_state ADD CONSTRAINT review_state_stage_check CHECK (stage BETWEEN -1 AND 4);
ALTER TABLE review_state ALTER COLUMN stage SET DEFAULT -1;
-- A monotonic version also survives undoing the first score (when no state row remains).
ALTER TABLE card ADD COLUMN review_version BIGINT NOT NULL DEFAULT 0 CHECK (review_version >= 0);
UPDATE card c SET review_version=r.version FROM review_state r WHERE r.user_id=c.user_id AND r.card_id=c.id;
ALTER TABLE session_item ADD COLUMN expected_review_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE session_item ADD COLUMN is_replay BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE session_item ADD COLUMN skipped BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_event ADD COLUMN event_order BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE review_event ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'RATE' CHECK (kind IN ('RATE','UNDO','RELEARN'));
ALTER TABLE review_event ADD COLUMN before_queue JSONB NOT NULL DEFAULT '[]';
ALTER TABLE review_event ADD COLUMN session_version_after BIGINT NOT NULL DEFAULT 0;
CREATE TABLE review_replay_allowance (
    user_id UUID NOT NULL, card_id UUID NOT NULL, learning_date DATE NOT NULL, session_id UUID NOT NULL, event_id UUID NOT NULL,
    PRIMARY KEY (user_id, card_id, learning_date), UNIQUE (user_id, session_id, card_id),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id,id),
    FOREIGN KEY (user_id, session_id) REFERENCES learning_session(user_id,id),
    FOREIGN KEY (user_id, event_id) REFERENCES review_event(user_id,id)
);
CREATE INDEX review_event_user_order ON review_event(user_id,event_order DESC);
