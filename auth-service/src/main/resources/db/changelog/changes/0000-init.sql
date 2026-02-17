--liquibase formatted sql

-- changeset taska:0000-create-schema
-- comment: Создание схемы базы данных.
CREATE SCHEMA IF NOT EXISTS taska;

-- changeset taska:0001-init-extensions
-- comment: Подключение необходимого расширения.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- changeset taska:0002-create-users
-- comment: Создание таблицы users.
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
   CONSTRAINT users_status_chk CHECK (status IN ('INVITED', 'ACTIVE', 'BLOCKED')),
   CONSTRAINT users_login_no_at_chk CHECK (position('@' in login) = 0)
);

-- changeset taska:0003-users-status-index
-- Индекс для ускорения выборки пользователей по статусу.
CREATE INDEX users_status_idx ON taska.users(status);

-- changeset taska:0004-create-credentials
-- comment: Создание таблицы credentials.
CREATE TABLE taska.credentials (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL,

    credential_type  varchar(32)  NOT NULL, -- PASSWORD | OAUTH | OIDC | SAML | API_TOKEN | WEBAUTHN
    provider         varchar(64)  NULL,     -- google | github | azure | okta | ...
    subject          varchar(256) NULL,     -- provider subject (OIDC sub / SAML NameID / etc.)
    secret_hash      varchar(255) NULL,     -- password hash / api token hash (never store raw secrets)

    algo             varchar(32)  NULL,     -- BCRYPT | ARGON2 (для PASSWORD / API_TOKEN if needed)
    meta             jsonb        NULL,     -- optional provider-specific data

    failed_attempts  integer      NOT NULL DEFAULT 0,
    last_failed_at   timestamptz  NULL,
    locked_until     timestamptz  NULL,

    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT credentials_user_fk FOREIGN KEY (user_id) REFERENCES taska.users (id) ON DELETE CASCADE,

    CONSTRAINT credentials_type_chk
        CHECK (credential_type IN ('PASSWORD', 'OAUTH', 'OIDC', 'SAML', 'API_TOKEN', 'WEBAUTHN')),
    CONSTRAINT credentials_algo_chk
        CHECK (algo IS NULL OR algo IN ('BCRYPT', 'ARGON2')),
    CONSTRAINT credentials_failed_attempts_chk
        CHECK (failed_attempts >= 0),
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

-- changeset taska:0005-create-credentials-uniqueness
-- comment: Ограничения уникальности для учётных данных.
CREATE UNIQUE INDEX credentials_one_password_per_user_uniq
    ON taska.credentials(user_id)
    WHERE credential_type = 'PASSWORD';

CREATE UNIQUE INDEX credentials_one_external_per_user_uniq
    ON taska.credentials(user_id, credential_type, provider)
    WHERE credential_type IN ('OAUTH', 'OIDC', 'SAML')
        AND provider IS NOT NULL;

CREATE UNIQUE INDEX credentials_external_identity_global_uniq
    ON taska.credentials(credential_type, provider, subject)
    WHERE credential_type IN ('OAUTH', 'OIDC', 'SAML')
        AND provider IS NOT NULL
        AND subject IS NOT NULL;


-- changeset taska:0006-create-refresh-tokens
-- comment: Создание таблицы refresh_tokens.
CREATE TABLE taska.refresh_tokens (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid         NOT NULL,
    token_hash   varchar(255) NOT NULL,
    issued_at    timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz  NULL,
    replaced_by  uuid         NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT refresh_tokens_token_hash_uniq UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_fk FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE,
    CONSTRAINT refresh_tokens_replaced_by_fk FOREIGN KEY (replaced_by) REFERENCES taska.refresh_tokens(id) ON DELETE SET NULL,
    CONSTRAINT refresh_tokens_expires_after_issued_chk CHECK (expires_at > issued_at)
);

-- changeset taska:0007-create-invite-tokens
-- comment: Создание таблицы invite_tokens.
CREATE TABLE taska.invite_tokens (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL,
    token_hash  varchar(255) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz  NULL,

    CONSTRAINT invite_tokens_user_fk FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE,
    CONSTRAINT invite_tokens_token_hash_uniq UNIQUE (token_hash),
    CONSTRAINT invite_tokens_expires_after_created_chk CHECK (expires_at > created_at)
);

-- changeset taska:0008-create-outbox-events
-- comment: Создание таблицы outbox_events.
CREATE TABLE taska.outbox_events (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz NULL,
    attempts       integer     NOT NULL DEFAULT 0,
    last_error_message text    NULL,

    CONSTRAINT outbox_events_attempts_chk CHECK (attempts >= 0)
);

-- changeset taska:0009-indexes
-- comment: Индексы для ускорения частых операций.
CREATE INDEX refresh_tokens_user_id_idx ON taska.refresh_tokens(user_id);
CREATE INDEX refresh_tokens_expires_at_idx ON taska.refresh_tokens(expires_at);
CREATE INDEX refresh_tokens_replaced_by_idx ON taska.refresh_tokens(replaced_by);

CREATE INDEX invite_tokens_user_id_idx ON taska.invite_tokens(user_id);
CREATE INDEX invite_tokens_expires_at_idx ON taska.invite_tokens(expires_at);
CREATE INDEX invite_tokens_used_at_idx ON taska.invite_tokens(used_at);

-- Индекс для выборки неопубликованных событий (воркер outbox)
CREATE INDEX outbox_events_unpublished_idx
    ON taska.outbox_events(created_at)
    WHERE published_at IS NULL;

CREATE INDEX outbox_events_aggregate_idx
    ON taska.outbox_events(aggregate_type, aggregate_id);
