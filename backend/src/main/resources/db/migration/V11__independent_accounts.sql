ALTER TABLE app_user ADD COLUMN account_origin VARCHAR(20) NOT NULL DEFAULT 'LEGACY'
    CHECK (account_origin IN ('LEGACY','PRESET','REGISTERED'));
ALTER TABLE app_user ADD COLUMN nickname VARCHAR(60);
ALTER TABLE app_user ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
-- Exact legacy login names remain valid; new names reserve their case-insensitive spelling.
CREATE TABLE account_name (normalized VARCHAR(100) PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id));
INSERT INTO account_name SELECT lower(username), min(id::text)::uuid FROM app_user GROUP BY lower(username);
CREATE TABLE preset_identity (id INTEGER PRIMARY KEY CHECK (id=1), user_id UUID NOT NULL UNIQUE REFERENCES app_user(id));
CREATE TABLE registration_invite (
    id UUID PRIMARY KEY, token_digest CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL, max_uses INTEGER NOT NULL CHECK(max_uses>0),
    used_count INTEGER NOT NULL DEFAULT 0 CHECK(used_count>=0 AND used_count<=max_uses),
    revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE registration_use (
    invite_id UUID NOT NULL REFERENCES registration_invite(id), user_id UUID PRIMARY KEY REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE account_admin_audit (
    id UUID PRIMARY KEY, action VARCHAR(32) NOT NULL, target_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE auth_rate_bucket (bucket CHAR(64) PRIMARY KEY, window_start TIMESTAMPTZ NOT NULL, attempts INTEGER NOT NULL);
CREATE TABLE capacity_guard (id INTEGER PRIMARY KEY CHECK(id=1));
INSERT INTO capacity_guard VALUES(1);
