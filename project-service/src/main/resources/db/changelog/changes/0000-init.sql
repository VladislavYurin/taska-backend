CREATE SCHEMA IF NOT EXISTS taska;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS taska.projects (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key text        NOT NULL,
    name        text        NOT NULL,
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_projects_project_key ON taska.projects(project_key);

CREATE INDEX IF NOT EXISTS idx_projects_created_by ON taska.projects(created_by);

CREATE TABLE IF NOT EXISTS taska.project_members (
    project_id  uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    role        text        NOT NULL,
    added_at    timestamptz NOT NULL,
    added_by    uuid        NOT NULL,

    CONSTRAINT pk_project_members PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_project_members_projects FOREIGN KEY (project_id) REFERENCES taska.projects(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_project_members_role
        CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER'))
);

CREATE INDEX IF NOT EXISTS idx_project_members_user_id ON taska.project_members(user_id);

CREATE INDEX IF NOT EXISTS idx_project_members_project_id_role ON taska.project_members(project_id, role);

CREATE TABLE IF NOT EXISTS taska.project_settings(
    project_id  uuid        PRIMARY KEY,
    settings    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid        NOT NULL,

    CONSTRAINT fk_project_settings_projects FOREIGN KEY (project_id) REFERENCES taska.projects(id)
        ON DELETE CASCADE
);

CREATE TABLE taska.outbox_events (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type      text        NOT NULL,
    aggregate_id        uuid        NOT NULL,
    event_type          text        NOT NULL,
    payload             jsonb       NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    published_at        timestamptz NULL,
    attempts            integer     NOT NULL DEFAULT 0,
    last_error_message  text        NULL,

    CONSTRAINT outbox_events_attempts_chk CHECK (attempts >= 0)
)