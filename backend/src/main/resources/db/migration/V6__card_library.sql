-- Personal organization and technical corrections never rewrite PDF evidence or scheduling.
ALTER TABLE card ADD COLUMN applicable_version VARCHAR(200);
ALTER TABLE card ADD COLUMN technical_correction TEXT NOT NULL DEFAULT '';
CREATE INDEX card_user_chapter ON card(user_id, chapter_id);
CREATE INDEX annotation_user_favorite ON personal_annotation(user_id, card_id) WHERE favorite;
