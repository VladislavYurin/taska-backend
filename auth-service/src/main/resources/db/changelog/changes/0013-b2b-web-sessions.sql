--liquibase formatted sql
-- changeset taska:0013-b2b-web-sessions
-- comment: Independent global and tenant web sessions; legacy refresh rows remain unbound until cutover.

ALTER TABLE taska.users ADD COLUMN auth_version bigint NOT NULL DEFAULT 1
    CONSTRAINT users_auth_version_positive_chk CHECK (auth_version > 0);

CREATE TABLE taska.web_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES taska.users(id) ON DELETE RESTRICT,
    state text NOT NULL CHECK (state IN ('ACTIVE','REVOKED','EXPIRED')),
    assurance text NOT NULL CHECK (assurance IN ('PASSWORD','SOCIAL','ADMIN_EMAIL_MFA')),
    auth_version bigint NOT NULL CHECK (auth_version > 0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT web_sessions_user_unique UNIQUE (id,user_id),
    CONSTRAINT web_sessions_expiry_check CHECK (expires_at > created_at)
);
CREATE INDEX web_sessions_user_state_idx ON taska.web_sessions(user_id,state,id);

CREATE TABLE taska.tenant_sessions (
    id uuid PRIMARY KEY,
    web_session_id uuid NOT NULL,
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    membership_version bigint NOT NULL CHECK (membership_version > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT tenant_sessions_web_fk FOREIGN KEY (web_session_id,user_id)
      REFERENCES taska.web_sessions(id,user_id) ON DELETE RESTRICT,
    CONSTRAINT tenant_sessions_web_unique UNIQUE (id,web_session_id),
    CONSTRAINT tenant_sessions_expiry_check CHECK (expires_at > created_at)
);
CREATE INDEX tenant_sessions_user_tenant_idx ON taska.tenant_sessions(user_id,tenant_id,id);
CREATE INDEX tenant_sessions_tenant_user_idx ON taska.tenant_sessions(tenant_id,user_id);

ALTER TABLE taska.refresh_tokens
    ADD COLUMN web_session_id uuid REFERENCES taska.web_sessions(id) ON DELETE RESTRICT,
    ADD COLUMN tenant_session_id uuid REFERENCES taska.tenant_sessions(id) ON DELETE RESTRICT,
    ADD COLUMN family_id uuid;
ALTER TABLE taska.refresh_tokens ADD CONSTRAINT refresh_tokens_session_shape CHECK (
    (web_session_id IS NULL AND tenant_session_id IS NULL AND family_id IS NULL)
    OR (web_session_id IS NOT NULL AND family_id IS NOT NULL)
);
ALTER TABLE taska.refresh_tokens ADD CONSTRAINT refresh_tokens_tenant_web_fk
    FOREIGN KEY (tenant_session_id,web_session_id)
    REFERENCES taska.tenant_sessions(id,web_session_id) ON DELETE RESTRICT;
CREATE INDEX refresh_tokens_family_idx ON taska.refresh_tokens(family_id)
    WHERE family_id IS NOT NULL;

CREATE TABLE taska.refresh_rotation_receipts (
    old_token_id uuid NOT NULL REFERENCES taska.refresh_tokens(id) ON DELETE CASCADE,
    idempotency_key uuid NOT NULL,
    nonce bytea,
    ciphertext bytea,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (old_token_id,idempotency_key),
    CONSTRAINT refresh_rotation_receipt_expiry_chk CHECK (expires_at > created_at),
    CONSTRAINT refresh_rotation_receipt_payload_chk CHECK ((nonce IS NULL) = (ciphertext IS NULL))
);
CREATE INDEX refresh_rotation_receipts_expiry_idx ON taska.refresh_rotation_receipts(expires_at);

CREATE TABLE taska.session_issuance_receipts (
    user_id uuid NOT NULL REFERENCES taska.users(id) ON DELETE RESTRICT,
    idempotency_key uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('GLOBAL_PASSWORD','GLOBAL_SOCIAL','TENANT_SELECT')),
    web_session_id uuid NOT NULL REFERENCES taska.web_sessions(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL,
    tenant_id uuid,
    membership_version bigint NOT NULL,
    family_id uuid NOT NULL,
    auth_version bigint NOT NULL,
    nonce bytea,
    ciphertext bytea,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (user_id,idempotency_key),
    CONSTRAINT session_issuance_receipt_expiry_chk CHECK (expires_at > created_at),
    CONSTRAINT session_issuance_receipt_payload_chk CHECK ((nonce IS NULL) = (ciphertext IS NULL))
);
CREATE INDEX session_issuance_receipts_expiry_idx ON taska.session_issuance_receipts(expires_at);
