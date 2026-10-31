-- Infrastructure only. User ownership and domain constraints start at P0-03.
CREATE TABLE application_metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    installation_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO application_metadata (id) VALUES (1);
