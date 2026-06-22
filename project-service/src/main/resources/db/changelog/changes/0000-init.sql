--liquibase formatted sql

-- changeset taska:0000-initial
-- comment: Инициализация миграций project-service

CREATE SCHEMA IF NOT EXISTS taska;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Projects
CREATE TABLE IF NOT EXISTS taska.projects
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    project_key text        NOT NULL UNIQUE,
    name        text        NOT NULL,
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz NULL
);

CREATE INDEX IF NOT EXISTS projects_created_by_idx
    ON taska.projects (created_by);

-- Project Members
CREATE TABLE IF NOT EXISTS taska.project_members
(
    project_id uuid        NOT NULL REFERENCES taska.projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,
    added_at   timestamptz NOT NULL DEFAULT now(),
    added_by   uuid        NOT NULL,

    PRIMARY KEY (project_id, user_id),

    CONSTRAINT ck_project_members_role CHECK
        (role IN ('ADMIN', 'MEMBER', 'VIEWER'))
);

CREATE INDEX IF NOT EXISTS project_members_user_idx
    ON taska.project_members (user_id);

CREATE INDEX IF NOT EXISTS project_members_project_role_idx
    ON taska.project_members (project_id, role);

-- Project Settings
CREATE TABLE IF NOT EXISTS taska.project_settings
(
    project_id uuid PRIMARY KEY REFERENCES taska.projects (id) ON DELETE CASCADE,
    settings   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid        NOT NULL
);

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