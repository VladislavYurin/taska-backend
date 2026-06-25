--liquibase formatted sql

-- changeset taska:0000-initial
-- comment: Инициализация миграций auth-service

CREATE SCHEMA IF NOT EXISTS taska;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Users
CREATE TABLE IF NOT EXISTS taska.users
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    login        varchar(64)  NOT NULL UNIQUE,
    email        varchar(320) NOT NULL UNIQUE,
    display_name varchar(128) NOT NULL,
    status       varchar(32)  NOT NULL DEFAULT 'INVITED',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT users_status_chk CHECK
        (status IN ('INVITED', 'ACTIVE', 'BLOCKED')),

    CONSTRAINT users_login_no_at_chk CHECK
        (position('@' in login) = 0)
);

CREATE INDEX IF NOT EXISTS users_status_idx ON taska.users (status);

-- Credentials
CREATE TABLE IF NOT EXISTS taska.credentials
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id         uuid         NOT NULL REFERENCES taska.users (id) ON DELETE CASCADE,
    credential_type varchar(32)  NOT NULL,
    provider        varchar(64)  NULL,
    subject         varchar(256) NULL,
    secret_hash     varchar(255) NULL,
    algo            varchar(32)  NULL,
    meta            jsonb        NULL,
    failed_attempts integer      NOT NULL DEFAULT 0,
    last_failed_at  timestamptz  NULL,
    locked_until    timestamptz  NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT credentials_type_chk CHECK
        (credential_type IN
         ('PASSWORD',
          'OAUTH',
          'OIDC',
          'SAML',
          'API_TOKEN',
          'WEBAUTHN')
        ),

    CONSTRAINT credentials_algo_chk CHECK
        (algo IS NULL OR algo IN ('BCRYPT', 'ARGON2')),

    CONSTRAINT credentials_failed_attempts_chk CHECK
        (failed_attempts >= 0),

    CONSTRAINT credentials_shape_chk CHECK (
        (credential_type = 'PASSWORD'
            AND provider IS NULL
            AND subject IS NULL
            AND secret_hash IS NOT NULL
            AND algo IS NOT NULL)
            OR
        (credential_type IN ('OAUTH', 'OIDC', 'SAML')
            AND provider IS NOT NULL
            AND subject IS NOT NULL
            AND secret_hash IS NULL)
            OR
        (credential_type = 'API_TOKEN'
            AND secret_hash IS NOT NULL
            AND provider IS NULL
            AND subject IS NULL)
            OR
        (credential_type = 'WEBAUTHN')
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS credentials_user_password_uniq
    ON taska.credentials (user_id)
    WHERE credential_type = 'PASSWORD';

CREATE UNIQUE INDEX IF NOT EXISTS credentials_external_uniq
    ON taska.credentials (credential_type, provider, subject)
    WHERE credential_type IN ('OAUTH', 'OIDC', 'SAML')
        AND provider IS NOT NULL
        AND subject IS NOT NULL;

-- Refresh Tokens
CREATE TABLE IF NOT EXISTS taska.refresh_tokens
(
    id          uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL REFERENCES taska.users (id) ON DELETE CASCADE,
    token_hash  varchar(255) NOT NULL UNIQUE,
    issued_at   timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz  NULL,
    replaced_by uuid         NULL REFERENCES taska.refresh_tokens (id) ON DELETE SET NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT refresh_tokens_expires_after_issued_chk CHECK
        (expires_at > issued_at)
);

CREATE INDEX IF NOT EXISTS refresh_tokens_user_idx
    ON taska.refresh_tokens (user_id);

CREATE INDEX IF NOT EXISTS refresh_tokens_expires_idx
    ON taska.refresh_tokens (expires_at);

CREATE INDEX IF NOT EXISTS refresh_tokens_replaced_idx
    ON taska.refresh_tokens (replaced_by);

-- Invite Tokens
CREATE TABLE IF NOT EXISTS taska.invite_tokens
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id    uuid         NOT NULL REFERENCES taska.users (id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL UNIQUE,
    created_at timestamptz  NOT NULL DEFAULT now(),
    expires_at timestamptz  NOT NULL,
    used_at    timestamptz  NULL,

    CONSTRAINT invite_tokens_expires_after_created_chk CHECK
        (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS invite_tokens_user_idx
    ON taska.invite_tokens (user_id);

CREATE INDEX IF NOT EXISTS invite_tokens_expires_idx
    ON taska.invite_tokens (expires_at);

CREATE INDEX IF NOT EXISTS invite_tokens_used_idx
    ON taska.invite_tokens (used_at);


-- Outbox Events
CREATE TABLE IF NOT EXISTS taska.outbox_events
(
    id                    uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    aggregate_type        text        NOT NULL,
    aggregate_id          uuid        NOT NULL,
    event_type            text        NOT NULL,
    payload               jsonb       NOT NULL,
    status                text        NOT NULL DEFAULT 'NEW',
    created_at            timestamptz NOT NULL DEFAULT now(),
    published_at          timestamptz NULL,
    attempts              integer     NOT NULL DEFAULT 0,
    last_error_message    text        NULL,
    processing_started_at timestamptz NULL,
    request_id            text        NULL,

    CONSTRAINT outbox_events_attempts_chk CHECK (attempts >= 0),

    CONSTRAINT outbox_events_status_chk CHECK
        (status IN ('NEW', 'PROCESSING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS outbox_events_unpublished_idx
    ON taska.outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON taska.outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS outbox_events_status_created_idx
    ON taska.outbox_events (status, created_at)
    WHERE status IN ('NEW', 'PROCESSING');

CREATE INDEX IF NOT EXISTS outbox_events_processing_idx
    ON taska.outbox_events (status, processing_started_at)
    WHERE status = 'PROCESSING'
        AND processing_started_at IS NOT NULL;