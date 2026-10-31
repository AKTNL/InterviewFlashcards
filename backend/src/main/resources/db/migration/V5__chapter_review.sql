ALTER TABLE draft_card ADD COLUMN parent_draft_id UUID;
ALTER TABLE draft_card ADD FOREIGN KEY (user_id, chapter_id, parent_draft_id) REFERENCES draft_card(user_id, chapter_id, id);
ALTER TABLE chapter ADD COLUMN abandoned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chapter_check_progress ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE draft_edit_operation ADD COLUMN count_delta INTEGER NOT NULL DEFAULT 0;
ALTER TABLE draft_edit_operation ADD COLUMN evidence JSONB NOT NULL DEFAULT '{}';
CREATE INDEX chapter_progress_recent ON chapter_check_progress(user_id, updated_at DESC);
