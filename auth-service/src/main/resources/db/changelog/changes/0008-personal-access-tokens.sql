--liquibase formatted sql
--changeset codex:0008-personal-access-tokens
CREATE TABLE taska.personal_access_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES taska.users(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    token_prefix varchar(16) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    scopes text[] NOT NULL CHECK (scopes = ARRAY['read']::text[] OR scopes = ARRAY['read','write']::text[]),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    last_used_at timestamptz NULL,
    revoked_at timestamptz NULL,
    revoked_reason varchar(64) NULL,
    CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX personal_access_tokens_owner_idx ON taska.personal_access_tokens(user_id, created_at DESC, id DESC);
