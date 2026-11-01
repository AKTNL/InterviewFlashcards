-- Platform namespaces reuse the content graph, but can never authenticate or become admins.
ALTER TABLE app_user ADD COLUMN space_kind VARCHAR(16) NOT NULL DEFAULT 'PERSONAL'
    CHECK(space_kind IN ('PERSONAL','PLATFORM'));
ALTER TABLE app_user ADD CONSTRAINT platform_identity_closed
    CHECK(space_kind <> 'PLATFORM' OR (password_hash='!' AND NOT enabled AND role='USER'));
CREATE TABLE platform_library (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    created_by UUID NOT NULL REFERENCES app_user(id),
    create_operation UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','UNLISTED','DELETED')),
    version BIGINT NOT NULL DEFAULT 0,
    content_version BIGINT NOT NULL DEFAULT 0,
    manifest JSONB NOT NULL DEFAULT '{}',
    snapshot JSONB NOT NULL DEFAULT '{}',
    deleted_at TIMESTAMPTZ,
    purged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(created_by,create_operation)
);
CREATE INDEX platform_library_catalog ON platform_library(status,created_at DESC);
CREATE TABLE platform_library_claim (
    library_id UUID NOT NULL REFERENCES platform_library(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    deck_id UUID NOT NULL,
    content_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(library_id,user_id)
);
CREATE TABLE platform_library_audit (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    library_id UUID,
    action VARCHAR(32) NOT NULL,
    version BIGINT,
    result VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
