ALTER TABLE source_document ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE mutation_receipt ADD CONSTRAINT mutation_receipt_digest CHECK (request_digest ~ '^[0-9a-f]{64}$');
