-- Stable IDs and composite ownership keys are the foundation for later business tasks.
-- Future workflows add their behavior and fields in new migrations; no placeholder API success.
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE CHECK (length(trim(username)) > 0),
    password_hash VARCHAR(100) NOT NULL,
    learning_timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Shanghai',
    data_epoch UUID NOT NULL DEFAULT gen_random_uuid(),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE source_document (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    original_filename VARCHAR(255) NOT NULL,
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    page_count INTEGER NOT NULL CHECK (page_count > 0),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    storage_key UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, sha256), UNIQUE (user_id, id)
);
CREATE TABLE import_job (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), source_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','PARSING','READY','FAILED')),
    stage VARCHAR(60), progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    error_code VARCHAR(100), parser_version VARCHAR(60),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id, source_id) REFERENCES source_document(user_id, id),
    UNIQUE (user_id, source_id), UNIQUE (user_id, id)
);
CREATE TABLE deck (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), name TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0), UNIQUE (user_id, id)
);
CREATE TABLE module (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), deck_id UUID NOT NULL,
    name TEXT NOT NULL, original_name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id, deck_id) REFERENCES deck(user_id, id), UNIQUE (user_id, id)
);
CREATE TABLE chapter (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), module_id UUID NOT NULL,
    name TEXT NOT NULL, original_name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
    check_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (check_status IN ('PENDING','IN_PROGRESS','IMPORTED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (user_id, module_id) REFERENCES module(user_id, id), UNIQUE (user_id, id)
);
CREATE TABLE draft_card (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), chapter_id UUID NOT NULL, import_job_id UUID NOT NULL,
    source_entry TEXT NOT NULL, question TEXT NOT NULL, content_blocks JSONB NOT NULL DEFAULT '[]',
    check_flags JSONB NOT NULL DEFAULT '[]', excluded BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (user_id, chapter_id) REFERENCES chapter(user_id, id),
    FOREIGN KEY (user_id, import_job_id) REFERENCES import_job(user_id, id),
    UNIQUE (user_id, import_job_id, source_entry), UNIQUE (user_id, chapter_id, id), UNIQUE (user_id, id)
);
CREATE TABLE draft_edit_operation (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), chapter_id UUID NOT NULL,
    operation_type VARCHAR(20) NOT NULL CHECK (operation_type IN ('EDIT','MERGE','SPLIT','EXCLUDE')),
    input_ids JSONB NOT NULL, output_ids JSONB NOT NULL, reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id, chapter_id) REFERENCES chapter(user_id, id), UNIQUE (user_id, id)
);
CREATE TABLE chapter_check_progress (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), chapter_id UUID NOT NULL, current_draft_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','IMPORTED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (user_id, chapter_id) REFERENCES chapter(user_id, id),
    FOREIGN KEY (user_id, chapter_id, current_draft_id) REFERENCES draft_card(user_id, chapter_id, id),
    UNIQUE (user_id, chapter_id), UNIQUE (user_id, id)
);
CREATE TABLE card (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), chapter_id UUID NOT NULL, draft_id UUID NOT NULL,
    parent_card_id UUID, question TEXT NOT NULL, original_content JSONB NOT NULL DEFAULT '[]',
    display_content JSONB NOT NULL DEFAULT '[]', needs_check BOOLEAN NOT NULL DEFAULT FALSE,
    display_revision BIGINT NOT NULL DEFAULT 0 CHECK (display_revision >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (user_id, chapter_id) REFERENCES chapter(user_id, id),
    FOREIGN KEY (user_id, chapter_id, draft_id) REFERENCES draft_card(user_id, chapter_id, id),
    UNIQUE (user_id, draft_id), UNIQUE (user_id, id),
    FOREIGN KEY (user_id, parent_card_id) REFERENCES card(user_id, id),
    CHECK (parent_card_id IS NULL OR parent_card_id <> id)
);
CREATE TABLE asset (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), source_id UUID NOT NULL,
    storage_key UUID NOT NULL UNIQUE, media_type VARCHAR(40) NOT NULL CHECK (media_type IN ('image/png','image/jpeg')),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0), sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id, source_id) REFERENCES source_document(user_id, id), UNIQUE (user_id, id)
);
CREATE TABLE source_span (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), source_id UUID NOT NULL,
    card_id UUID, draft_id UUID, asset_id UUID, physical_page INTEGER NOT NULL CHECK (physical_page > 0),
    region JSONB, sort_order INTEGER NOT NULL DEFAULT 0,
    CHECK ((card_id IS NOT NULL)::integer + (draft_id IS NOT NULL)::integer = 1),
    FOREIGN KEY (user_id, source_id) REFERENCES source_document(user_id, id),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id),
    FOREIGN KEY (user_id, draft_id) REFERENCES draft_card(user_id, id),
    FOREIGN KEY (user_id, asset_id) REFERENCES asset(user_id, id), UNIQUE (user_id, id)
);
CREATE TABLE follow_up (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), card_id UUID, draft_id UUID,
    content JSONB NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
    CHECK ((card_id IS NOT NULL)::integer + (draft_id IS NOT NULL)::integer = 1),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id),
    FOREIGN KEY (user_id, draft_id) REFERENCES draft_card(user_id, id), UNIQUE (user_id, id)
);
CREATE TABLE tag (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), name TEXT NOT NULL,
    UNIQUE (user_id, name), UNIQUE (user_id, id)
);
CREATE TABLE card_tag (
    user_id UUID NOT NULL REFERENCES app_user(id), card_id UUID NOT NULL, tag_id UUID NOT NULL,
    PRIMARY KEY (user_id, card_id, tag_id),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id), FOREIGN KEY (user_id, tag_id) REFERENCES tag(user_id, id)
);
CREATE TABLE personal_annotation (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), card_id UUID NOT NULL,
    notes TEXT NOT NULL DEFAULT '', hint TEXT NOT NULL DEFAULT '', memory_points TEXT NOT NULL DEFAULT '',
    favorite BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id), UNIQUE (user_id, card_id), UNIQUE (user_id, id)
);
CREATE TABLE learning_preference (
    id UUID PRIMARY KEY, user_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    daily_new_count INTEGER NOT NULL DEFAULT 5 CHECK (daily_new_count >= 0),
    review_batch_size INTEGER NOT NULL DEFAULT 20 CHECK (review_batch_size > 0),
    reference_minutes INTEGER CHECK (reference_minutes > 0), version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (user_id, id)
);
CREATE TABLE learning_preference_chapter (
    user_id UUID NOT NULL REFERENCES app_user(id), preference_id UUID NOT NULL, chapter_id UUID NOT NULL,
    PRIMARY KEY (user_id, preference_id, chapter_id),
    FOREIGN KEY (user_id, preference_id) REFERENCES learning_preference(user_id, id),
    FOREIGN KEY (user_id, chapter_id) REFERENCES chapter(user_id, id)
);
CREATE TABLE review_state (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), card_id UUID NOT NULL,
    first_learned_at TIMESTAMPTZ NOT NULL, last_reviewed_at TIMESTAMPTZ NOT NULL, due_date DATE NOT NULL,
    stage INTEGER NOT NULL DEFAULT 0 CHECK (stage >= 0), latest_rating VARCHAR(20) NOT NULL CHECK (latest_rating IN ('FORGOT','VAGUE','REMEMBERED')),
    lapse_count INTEGER NOT NULL DEFAULT 0 CHECK (lapse_count >= 0), version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id), UNIQUE (user_id, card_id), UNIQUE (user_id, id)
);
CREATE TABLE learning_session (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), learning_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','INVALIDATED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0), UNIQUE (user_id, id)
);
CREATE TABLE session_item (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), session_id UUID NOT NULL, card_id UUID NOT NULL,
    sort_order INTEGER NOT NULL, completed BOOLEAN NOT NULL DEFAULT FALSE, replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    FOREIGN KEY (user_id, session_id) REFERENCES learning_session(user_id, id),
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id), UNIQUE (user_id, session_id, sort_order), UNIQUE (user_id, id)
);
CREATE TABLE review_event (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), data_epoch UUID NOT NULL, operation_id UUID NOT NULL,
    card_id UUID NOT NULL, session_item_id UUID NOT NULL, rating VARCHAR(20) NOT NULL CHECK (rating IN ('FORGOT','VAGUE','REMEMBERED')),
    before_state JSONB NOT NULL, after_state JSONB NOT NULL, algorithm_version VARCHAR(60) NOT NULL,
    learning_date DATE NOT NULL, is_replay BOOLEAN NOT NULL DEFAULT FALSE, undo_of UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id, card_id) REFERENCES card(user_id, id),
    FOREIGN KEY (user_id, session_item_id) REFERENCES session_item(user_id, id),
    UNIQUE (user_id, data_epoch, operation_id), UNIQUE (user_id, id), UNIQUE (user_id, undo_of),
    FOREIGN KEY (user_id, undo_of) REFERENCES review_event(user_id, id), CHECK (undo_of IS NULL OR undo_of <> id)
);
CREATE TABLE mutation_receipt (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), data_epoch UUID NOT NULL, operation_id UUID NOT NULL,
    request_digest VARCHAR(64) NOT NULL, result JSONB NOT NULL, revision BIGINT NOT NULL CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (user_id, data_epoch, operation_id), UNIQUE (user_id, id)
);
CREATE TABLE sync_conflict (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), entity_id UUID NOT NULL,
    entity_type VARCHAR(60) NOT NULL, client_content JSONB NOT NULL, server_content JSONB NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (user_id, id)
);
CREATE TABLE backup_job (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','READY','FAILED')),
    format_version INTEGER, storage_key UUID, error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (user_id, id)
);
CREATE TABLE restore_job (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','VALIDATING','AWAITING_CONFIRMATION','RESTORING','READY','FAILED')),
    format_version INTEGER, storage_key UUID, validation_result JSONB, error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (user_id, id)
);
CREATE INDEX source_document_user_created ON source_document(user_id, created_at DESC);
CREATE INDEX review_state_user_due ON review_state(user_id, due_date);
CREATE INDEX review_event_user_date ON review_event(user_id, learning_date);
