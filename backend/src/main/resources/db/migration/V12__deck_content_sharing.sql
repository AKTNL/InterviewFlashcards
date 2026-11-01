-- A claimed PDF has its own namespace even if the recipient already uploaded identical bytes.
ALTER TABLE source_document ADD COLUMN shared_copy BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_document DROP CONSTRAINT source_document_user_id_sha256_key;
CREATE UNIQUE INDEX source_upload_fingerprint ON source_document(user_id,sha256) WHERE NOT shared_copy;
ALTER TABLE deck ADD COLUMN copy_origin JSONB NOT NULL DEFAULT '{}';

-- Invitations and snapshot files are deliberately outside the personal backup whitelist.
CREATE TABLE deck_share (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    recipient_id UUID NOT NULL REFERENCES app_user(id),
    owner_epoch UUID NOT NULL,
    manifest JSONB NOT NULL,
    snapshot JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    claim_count INTEGER NOT NULL DEFAULT 0 CHECK (claim_count BETWEEN 0 AND 1),
    max_claims INTEGER NOT NULL DEFAULT 1 CHECK (max_claims=1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (user_id<>recipient_id), UNIQUE(user_id,id)
);
CREATE TABLE share_resource (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, share_id UUID NOT NULL,
    storage_key UUID NOT NULL UNIQUE, byte_size BIGINT NOT NULL CHECK(byte_size>0),
    FOREIGN KEY(user_id,share_id) REFERENCES deck_share(user_id,id)
);
CREATE INDEX deck_share_recipient ON deck_share(recipient_id,created_at DESC);
