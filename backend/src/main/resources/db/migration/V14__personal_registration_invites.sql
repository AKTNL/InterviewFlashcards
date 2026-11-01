CREATE TABLE registration_guard (id INTEGER PRIMARY KEY CHECK(id=1));
INSERT INTO registration_guard VALUES(1);
CREATE TABLE invitation_permission (
    user_id UUID PRIMARY KEY REFERENCES app_user(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    total_limit INTEGER NOT NULL DEFAULT 0 CHECK(total_limit>=0),
    successful_count INTEGER NOT NULL DEFAULT 0 CHECK(successful_count>=0)
);
ALTER TABLE registration_invite ADD COLUMN inviter_id UUID REFERENCES invitation_permission(user_id);
ALTER TABLE registration_invite ADD COLUMN invite_source VARCHAR(16) NOT NULL DEFAULT 'MAINTENANCE'
    CHECK(invite_source IN ('MAINTENANCE','PERSONAL'));
ALTER TABLE registration_invite ADD CONSTRAINT invite_owner_source CHECK
    ((invite_source='MAINTENANCE' AND inviter_id IS NULL) OR (invite_source='PERSONAL' AND inviter_id IS NOT NULL AND max_uses=1));
CREATE INDEX registration_invite_owner ON registration_invite(inviter_id,created_at DESC);
