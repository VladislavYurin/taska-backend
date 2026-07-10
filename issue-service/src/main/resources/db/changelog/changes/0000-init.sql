--liquibase formatted sql

-- changeset taska:0000-initial
-- comment: Инициализация миграций issue-service

CREATE SCHEMA IF NOT EXISTS taska;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Issues
CREATE TABLE IF NOT EXISTS taska.issues
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    project_id   uuid        NOT NULL,
    issue_number int         NOT NULL,
    issue_key    text        NOT NULL UNIQUE,
    issue_type   text        NOT NULL,
    summary      text        NOT NULL,
    description  text        NULL,
    status_key   text        NOT NULL,
    priority     text        NOT NULL,
    assignee_id  uuid        NULL,
    reporter_id  uuid        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      int         NOT NULL DEFAULT 1,
    deleted_at   timestamptz NULL,

    CONSTRAINT issues_project_number_uniq UNIQUE
        (project_id, issue_number),

    CONSTRAINT issues_type_chk CHECK
        (issue_type IN ('TASK', 'BUG', 'STORY')),

    CONSTRAINT issues_priority_chk CHECK
        (priority IN ('LOW', 'MEDIUM', 'HIGH')),

    CONSTRAINT issues_version_chk CHECK (version >= 1)
);

CREATE INDEX IF NOT EXISTS issues_project_status_idx
    ON taska.issues (project_id, status_key);

CREATE INDEX IF NOT EXISTS issues_project_assignee_idx
    ON taska.issues (project_id, assignee_id);

CREATE INDEX IF NOT EXISTS issues_project_updated_at_idx
    ON taska.issues (project_id, updated_at);

CREATE INDEX IF NOT EXISTS issues_status_key_idx
    ON taska.issues (status_key);

CREATE INDEX IF NOT EXISTS issues_id_active_idx
    ON taska.issues (id)
    WHERE deleted_at IS NULL;

-- Project Counters
CREATE TABLE IF NOT EXISTS taska.project_counters
(
    project_id        uuid PRIMARY KEY,
    next_issue_number int NOT NULL DEFAULT 1,

    CONSTRAINT project_counters_next_issue_number_chk CHECK
        (next_issue_number >= 1)
);

-- Issue History
CREATE TABLE IF NOT EXISTS taska.issue_history
(
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    issue_id      uuid        NOT NULL REFERENCES taska.issues (id) ON DELETE CASCADE,
    event_type    text        NOT NULL,
    actor_user_id uuid        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT issue_history_event_type_chk CHECK
        (event_type IN (
                        'CREATED', 'DELETED', 'UPDATED',
                        'ASSIGNED', 'TRANSITIONED'
            )
        )
);

CREATE INDEX IF NOT EXISTS issue_history_issue_occurred_idx
    ON taska.issue_history (issue_id, occurred_at);

CREATE INDEX IF NOT EXISTS issue_history_actor_occurred_idx
    ON taska.issue_history (actor_user_id, occurred_at);

-- Issue Links
CREATE TABLE IF NOT EXISTS taska.issue_links
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    source_issue_id uuid        NOT NULL REFERENCES taska.issues (id) ON DELETE CASCADE,
    target_issue_id uuid        NOT NULL REFERENCES taska.issues (id) ON DELETE CASCADE,
    link_type       text        NOT NULL,
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT issue_links_unique UNIQUE
        (source_issue_id, target_issue_id, link_type),

    CONSTRAINT issue_links_link_type_chk CHECK
        (link_type IN ('BLOCKS', 'RELATES', 'DUPLICATES'))
);

CREATE INDEX IF NOT EXISTS issue_links_source_issue_idx
    ON taska.issue_links (source_issue_id);

CREATE INDEX IF NOT EXISTS issue_links_target_issue_idx
    ON taska.issue_links (target_issue_id);

-- Idempotency Keys
CREATE TABLE IF NOT EXISTS taska.idempotency_keys
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    key          text        NOT NULL,
    user_id      uuid        NOT NULL,
    request_hash text        NOT NULL,
    response     jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_user_key_uniq UNIQUE (user_id, key)
);

CREATE INDEX IF NOT EXISTS idempotency_keys_expires_at_idx
    ON taska.idempotency_keys (expires_at);

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