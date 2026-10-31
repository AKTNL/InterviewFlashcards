ALTER TABLE import_job ADD COLUMN raw_evidence JSONB;
ALTER TABLE import_job ADD COLUMN report JSONB NOT NULL DEFAULT '{}';
ALTER TABLE import_job ADD COLUMN error_message TEXT;
ALTER TABLE import_job ADD COLUMN attempt_token UUID;
ALTER TABLE import_job ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE import_job ADD COLUMN deck_id UUID;
ALTER TABLE import_job ADD FOREIGN KEY (user_id, deck_id) REFERENCES deck(user_id, id);
ALTER TABLE draft_card ADD COLUMN original_content JSONB NOT NULL DEFAULT '[]';
ALTER TABLE draft_card ADD COLUMN source_metadata JSONB NOT NULL DEFAULT '{}';
ALTER TABLE draft_card ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
CREATE INDEX import_job_pending ON import_job(status, created_at);
CREATE INDEX draft_card_chapter_order ON draft_card(user_id, chapter_id, sort_order);
-- Sources registered before P0-05 get the same resumable workflow.
INSERT INTO import_job(id,user_id,source_id,stage)
SELECT gen_random_uuid(),s.user_id,s.id,'WAITING' FROM source_document s
WHERE NOT EXISTS (SELECT 1 FROM import_job j WHERE j.user_id=s.user_id AND j.source_id=s.id);
