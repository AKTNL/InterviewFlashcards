-- Independent from personal learning epochs, revisions and ZIP replacement.
CREATE TABLE usage_collection (
    id SMALLINT PRIMARY KEY CHECK(id=1), started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO usage_collection(id) VALUES(1);
CREATE TABLE usage_user (
    user_id UUID PRIMARY KEY REFERENCES app_user(id), last_active_at TIMESTAMPTZ
);
-- A closed login remains a tombstone so an in-flight heartbeat cannot reopen it.
CREATE TABLE usage_login (
    auth_hash VARCHAR(64) PRIMARY KEY,
    ended BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE usage_stream (
    user_id UUID NOT NULL REFERENCES app_user(id), auth_hash VARCHAR(64) NOT NULL,
    stream_id UUID NOT NULL, credential_version BIGINT NOT NULL,
    sequence BIGINT NOT NULL CHECK(sequence>=0), received_at TIMESTAMPTZ NOT NULL,
    stopped BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(user_id,auth_hash,stream_id)
);
CREATE INDEX usage_stream_received ON usage_stream(received_at);
CREATE INDEX usage_stream_auth ON usage_stream(auth_hash);
CREATE TABLE usage_daily (
    user_id UUID NOT NULL REFERENCES app_user(id), day DATE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    seconds DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK(seconds>=0 AND seconds<=86400),
    spans TSTZMULTIRANGE DEFAULT '{}'::tstzmultirange,
    PRIMARY KEY(user_id,day)
);
CREATE INDEX usage_daily_day ON usage_daily(day,user_id);
CREATE INDEX review_event_dashboard_time ON review_event(created_at,user_id);
