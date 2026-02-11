--liquibase formatted sql

-- changeset taska:0000-create-schema
CREATE SCHEMA IF NOT EXISTS taska;

-- changeset taska:0001-init-extensions
-- comment: Enable required extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- changeset taska:0002-create-users
-- comment: Create users table
CREATE TABLE taska.users (
   id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
   login         varchar(64) NOT NULL,
   email         varchar(320) NOT NULL,
   display_name  varchar(128) NOT NULL,
   status        varchar(32) NOT NULL,
   lockout_until timestamptz NULL,
   created_at    timestamptz NOT NULL DEFAULT now(),
   updated_at    timestamptz NOT NULL DEFAULT now(),

   CONSTRAINT users_login_uniq UNIQUE (login),
   CONSTRAINT users_email_uniq UNIQUE (email),
   CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'BLOCKED', 'DISABLED')),
   CONSTRAINT users_login_no_at_chk CHECK (position('@' in login) = 0)
);

-- changeset taska:0003-create-credentials
-- comment: Create credentials table
CREATE TABLE taska.credentials
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL,

    credential_type varchar(32) NOT NULL, -- PASSWORD | OAUTH | OIDC | SAML | API_TOKEN | WEBAUTHN
    provider        varchar(64) NULL,     -- google | github | azure | okta | ...
    subject         varchar(256) NULL,    -- provider subject (OIDC sub / SAML NameID / etc.)
    secret_hash     varchar(255) NULL,    -- password hash / api token hash (never store raw secrets)

    algo            varchar(32) NULL,     -- BCRYPT | ARGON2 (for PASSWORD / API_TOKEN if needed)
    meta            jsonb NULL,           -- optional provider-specific data

    failed_attempts integer     NOT NULL DEFAULT 0,
    last_failed_at  timestamptz NULL,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT credentials_user_fk FOREIGN KEY (user_id) REFERENCES taska.users (id) ON DELETE CASCADE,

    CONSTRAINT credentials_type_chk CHECK (credential_type IN
                                           ('PASSWORD', 'OAUTH', 'OIDC', 'SAML', 'API_TOKEN', 'WEBAUTHN')),
    CONSTRAINT credentials_algo_chk CHECK (algo IS NULL OR algo IN ('BCRYPT', 'ARGON2')),
    CONSTRAINT credentials_failed_attempts_chk CHECK (failed_attempts >= 0),

    CONSTRAINT credentials_shape_chk CHECK (
        (credential_type = 'PASSWORD' AND provider IS NULL AND subject IS NULL AND secret_hash IS NOT NULL AND algo IS NOT NULL)
            OR
        (credential_type IN ('OAUTH', 'OIDC', 'SAML') AND provider IS NOT NULL AND subject IS NOT NULL AND secret_hash IS NULL)
            OR
        (credential_type = 'API_TOKEN' AND secret_hash IS NOT NULL AND provider IS NULL AND subject IS NULL)
            OR
        (credential_type = 'WEBAUTHN')
    )
);

-- changeset taska:0004-create-credentials-uniqueness
-- comment: Uniqueness rules for credentials
-- One PASSWORD credential per user
CREATE UNIQUE INDEX credentials_one_password_per_user_uniq
    ON taska.credentials(user_id)
    WHERE credential_type = 'PASSWORD';

-- One API_TOKEN per user per (provider) is optional; for MVP we allow multiple tokens per user:
-- remove/adjust later if you want single token.

-- Prevent duplicates for external providers per user (e.g. one google OIDC)
CREATE UNIQUE INDEX credentials_one_external_per_user_uniq
    ON taska.credentials(user_id, credential_type, provider)
    WHERE credential_type IN ('OAUTH', 'OIDC', 'SAML')
        AND provider IS NOT NULL;

-- External identity must map to only one user (provider+subject unique per type)
CREATE UNIQUE INDEX credentials_external_identity_global_uniq
    ON taska.credentials(credential_type, provider, subject)
    WHERE credential_type IN ('OAUTH', 'OIDC', 'SAML')
        AND provider IS NOT NULL
        AND subject IS NOT NULL;


-- changeset taska:0005-create-refresh-tokens
-- comment: Create refresh_tokens table
CREATE TABLE taska.refresh_tokens (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid         NOT NULL,
    token_hash    varchar(255) NOT NULL,
    issued_at     timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    revoked_at    timestamptz  NULL,
    replaced_by   uuid         NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT refresh_tokens_token_hash_uniq UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_fk FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE,
    CONSTRAINT refresh_tokens_replaced_by_fk FOREIGN KEY (replaced_by) REFERENCES taska.refresh_tokens(id) ON DELETE SET NULL,
    CONSTRAINT refresh_tokens_expires_after_issued_chk CHECK (expires_at > issued_at)
);

-- changeset taska:0006-indexes
-- comment: Add indexes for common access patterns
CREATE INDEX refresh_tokens_user_id_idx ON taska.refresh_tokens(user_id);
CREATE INDEX refresh_tokens_expires_at_idx ON taska.refresh_tokens(expires_at);
CREATE INDEX refresh_tokens_replaced_by_idx ON taska.refresh_tokens(replaced_by);
