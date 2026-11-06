--liquibase formatted sql
-- changeset taska:0014-tenant-personal-tokens
-- comment: TAS-463 tenant-bound PAT grants; legacy PAT rows stay unbound until cutover.

CREATE TABLE taska.integration_grants (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES taska.users(id) ON DELETE RESTRICT,
    kind text NOT NULL CHECK (kind IN ('USER','ADMIN')),
    scope_type text NOT NULL CHECK (scope_type IN ('ONE_TENANT','TENANT_SET','PLATFORM')),
    tenant_id uuid,
    access_level text NOT NULL CHECK (access_level IN ('READ','MODIFY')),
    permissions text[] NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT integration_grants_user_unique UNIQUE (id,user_id),
    CONSTRAINT integration_grants_tenant_shape CHECK ((scope_type='ONE_TENANT') = (tenant_id IS NOT NULL)),
    CONSTRAINT integration_grants_user_shape CHECK (
        kind='ADMIN' OR (scope_type='ONE_TENANT' AND NOT ('ACCOUNT_ADMIN'=ANY(permissions)))),
    CONSTRAINT integration_grants_admin_shape CHECK (kind<>'ADMIN' OR scope_type IN ('TENANT_SET','PLATFORM')),
    CONSTRAINT integration_grants_expiry_check CHECK (expires_at IS NULL OR expires_at > created_at)
);
CREATE INDEX integration_grants_user_idx ON taska.integration_grants(user_id,revoked_at,id);
CREATE INDEX integration_grants_tenant_user_idx ON taska.integration_grants(tenant_id,user_id)
    WHERE tenant_id IS NOT NULL;

ALTER TABLE taska.personal_access_tokens ADD COLUMN integration_grant_id uuid;
ALTER TABLE taska.personal_access_tokens ADD CONSTRAINT personal_access_tokens_grant_fk
    FOREIGN KEY (integration_grant_id,user_id) REFERENCES taska.integration_grants(id,user_id)
    ON DELETE RESTRICT;
CREATE UNIQUE INDEX personal_access_tokens_grant_idx ON taska.personal_access_tokens(integration_grant_id)
    WHERE integration_grant_id IS NOT NULL;

CREATE TABLE taska.personal_token_issuance_receipts (
    user_id uuid NOT NULL REFERENCES taska.users(id) ON DELETE RESTRICT,
    idempotency_key uuid NOT NULL,
    request_sha256 bytea NOT NULL CHECK (length(request_sha256) = 32),
    token_id uuid NOT NULL REFERENCES taska.personal_access_tokens(id) ON DELETE RESTRICT,
    nonce bytea,
    ciphertext bytea,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (user_id,idempotency_key),
    CONSTRAINT personal_token_receipt_expiry_chk CHECK (expires_at > created_at),
    CONSTRAINT personal_token_receipt_payload_chk CHECK ((nonce IS NULL) = (ciphertext IS NULL))
);
CREATE INDEX personal_token_issuance_receipts_expiry_idx ON taska.personal_token_issuance_receipts(expires_at);
