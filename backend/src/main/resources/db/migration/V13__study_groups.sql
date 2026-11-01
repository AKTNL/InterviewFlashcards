-- Social membership/consent deliberately stays outside personal backup/restore tables.
CREATE TABLE study_group (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE study_group_member (
    group_id UUID NOT NULL REFERENCES study_group(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sharing BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(group_id,user_id),
    CHECK(active OR NOT sharing)
);
CREATE INDEX study_group_member_user ON study_group_member(user_id,group_id) WHERE active;
CREATE TABLE study_group_invite (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES study_group(id),
    recipient_id UUID NOT NULL REFERENCES app_user(id),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    accepted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX study_group_invite_recipient ON study_group_invite(recipient_id,created_at DESC);
