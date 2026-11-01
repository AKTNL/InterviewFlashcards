ALTER TABLE app_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK(role IN ('USER','ADMIN'));
ALTER TABLE app_user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN admin_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN last_login_at TIMESTAMPTZ;
ALTER TABLE app_user DROP CONSTRAINT app_user_account_origin_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_account_origin_check CHECK(account_origin IN ('LEGACY','PRESET','REGISTERED','ADMIN_CREATED'));
-- Separate permanent allocations preserve existing invitation accounting and survive personal restore.
CREATE TABLE admin_account_allocation(user_id UUID PRIMARY KEY REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE admin_create_receipt(operation_id UUID PRIMARY KEY, actor_id UUID NOT NULL REFERENCES app_user(id), target_id UUID NOT NULL REFERENCES app_user(id), request_hash VARCHAR(100) NOT NULL);
ALTER TABLE account_admin_audit ADD COLUMN actor_id UUID REFERENCES app_user(id);
ALTER TABLE account_admin_audit ADD COLUMN actor_source VARCHAR(16) NOT NULL DEFAULT 'MAINTENANCE';
ALTER TABLE account_admin_audit ADD COLUMN before_value JSONB NOT NULL DEFAULT '{}';
ALTER TABLE account_admin_audit ADD COLUMN after_value JSONB NOT NULL DEFAULT '{}';
ALTER TABLE account_admin_audit ADD COLUMN result VARCHAR(32) NOT NULL DEFAULT 'SUCCESS';
CREATE INDEX account_admin_audit_target ON account_admin_audit(target_id,created_at DESC);
CREATE INDEX app_user_admin_list ON app_user(created_at DESC,id);
