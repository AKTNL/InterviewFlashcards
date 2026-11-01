-- Revision coverage and row identities only: content is read from authoritative tables.
CREATE TABLE sync_revision (
    user_id UUID NOT NULL REFERENCES app_user(id), data_epoch UUID NOT NULL,
    revision BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, data_epoch, revision)
);
CREATE TABLE sync_row_change (
    user_id UUID NOT NULL REFERENCES app_user(id), data_epoch UUID NOT NULL,
    revision BIGINT NOT NULL, table_name TEXT NOT NULL, row_key JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, data_epoch, revision, table_name, row_key)
);
CREATE INDEX sync_revision_retention ON sync_revision(created_at);
CREATE INDEX sync_row_change_retention ON sync_row_change(created_at);

CREATE FUNCTION record_sync_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision <> OLD.revision OR NEW.data_epoch <> OLD.data_epoch THEN
        INSERT INTO sync_revision(user_id,data_epoch,revision) VALUES(NEW.id,NEW.data_epoch,NEW.revision)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER sync_revision_changed AFTER UPDATE ON app_user
FOR EACH ROW EXECUTE FUNCTION record_sync_revision();

-- Deferred until commit: workers, mutations and restore advance the owner revision
-- after their business writes. Rollback also rolls back every identity and marker.
CREATE FUNCTION record_sync_row() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    row_value JSONB; row_identity JSONB; owner_epoch UUID; owner_revision BIGINT;
BEGIN
    FOR row_value IN SELECT v FROM (VALUES
        (CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END),
        (CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END)) AS rows(v) WHERE v IS NOT NULL
    LOOP
        row_identity := CASE TG_TABLE_NAME
            WHEN 'card_tag' THEN jsonb_build_object('card_id',row_value->'card_id','tag_id',row_value->'tag_id')
            WHEN 'learning_preference_chapter' THEN jsonb_build_object('preference_id',row_value->'preference_id','chapter_id',row_value->'chapter_id')
            WHEN 'review_policy' THEN jsonb_build_object('version',row_value->'version')
            WHEN 'review_replay_allowance' THEN jsonb_build_object('card_id',row_value->'card_id','learning_date',row_value->'learning_date')
            ELSE jsonb_build_object('id',row_value->'id') END;
        SELECT data_epoch,revision INTO owner_epoch,owner_revision FROM app_user WHERE id=(row_value->>'user_id')::uuid;
        INSERT INTO sync_row_change(user_id,data_epoch,revision,table_name,row_key)
        VALUES((row_value->>'user_id')::uuid,owner_epoch,owner_revision,TG_TABLE_NAME,row_identity)
        ON CONFLICT DO NOTHING;
    END LOOP;
    RETURN NULL;
END $$;
DO $$ DECLARE relation TEXT;
BEGIN
    FOREACH relation IN ARRAY ARRAY['source_document','deck','module','chapter','draft_card','draft_edit_operation',
        'chapter_check_progress','card','asset','source_span','follow_up','tag','card_tag','personal_annotation',
        'review_policy','learning_preference','learning_preference_chapter','review_state','review_event',
        'learning_session','session_item','review_replay_allowance','import_job','sync_conflict']
    LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER sync_row_changed AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION record_sync_row()',relation);
    END LOOP;
END $$;
