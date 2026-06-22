--liquibase formatted sql

-- changeset taska:0000-initial
-- comment: Инициализация миграций workflow-service

CREATE SCHEMA IF NOT EXISTS taska;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Workflows
CREATE TABLE IF NOT EXISTS taska.workflows
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    version    integer     NOT NULL DEFAULT 1,
    is_active  boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT workflows_version_chk CHECK (version >= 1)
);

-- Statuses
CREATE TABLE IF NOT EXISTS taska.statuses
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workflow_id uuid        NOT NULL REFERENCES taska.workflows (id) ON DELETE CASCADE,
    status_key  text        NOT NULL,
    name        text        NOT NULL,
    category    text        NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT statuses_workflow_status_uniq UNIQUE
        (workflow_id, status_key),

    CONSTRAINT statuses_sort_positive_chk CHECK (sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS statuses_workflow_idx
    ON taska.statuses (workflow_id);

-- Transitions
CREATE TABLE IF NOT EXISTS taska.transitions
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    workflow_id    uuid        NOT NULL REFERENCES taska.workflows (id) ON DELETE CASCADE,
    from_status_id uuid        NOT NULL REFERENCES taska.statuses (id) ON DELETE CASCADE,
    to_status_id   uuid        NOT NULL REFERENCES taska.statuses (id) ON DELETE CASCADE,
    name           text        NOT NULL,
    is_hidden      boolean     NOT NULL DEFAULT false,
    sort_order     integer     NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT transitions_from_to_status_chk CHECK
        (from_status_id <> to_status_id),

    CONSTRAINT transitions_sort_positive_chk CHECK
        (sort_order >= 0)
);

CREATE INDEX IF NOT EXISTS transitions_workflow_from_idx
    ON taska.transitions (workflow_id, from_status_id);

CREATE INDEX IF NOT EXISTS transitions_workflow_to_idx
    ON taska.transitions (workflow_id, to_status_id);

-- Validator Rules
CREATE TABLE IF NOT EXISTS taska.validator_rules
(
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    transition_id uuid        NOT NULL REFERENCES taska.transitions (id) ON DELETE CASCADE,
    rule_type     text        NOT NULL,
    config        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    error_code    text        NULL,
    error_message text        NULL,
    sort_order    integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT validator_rules_sort_positive_chk CHECK
        (sort_order >= 0),

    CONSTRAINT validator_rules_transition_rule_type_sort_uniq UNIQUE
        (transition_id, rule_type, sort_order),

    CONSTRAINT validator_rules_rule_type_chk CHECK
        (rule_type IN ('REQUIRE_FIELD',
                       'REQUIRE_ASSIGNEE',
                       'REQUIRE_COMMENT')
        )
);

CREATE INDEX IF NOT EXISTS validator_rules_transition_idx
    ON taska.validator_rules (transition_id);

-- Workflow Bindings
CREATE TABLE IF NOT EXISTS taska.workflow_bindings
(
    project_id  uuid        NOT NULL,
    issue_type  text        NOT NULL,
    workflow_id uuid        NOT NULL REFERENCES taska.workflows (id) ON DELETE RESTRICT,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (project_id, issue_type)
);

CREATE INDEX IF NOT EXISTS workflow_bindings_workflow_idx
    ON taska.workflow_bindings (workflow_id);

-- Default Workflow Seed
INSERT INTO taska.workflows (id, name, version, is_active, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111'::uuid,
        'Default workflow',
        1,
        true,
        now(),
        now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO taska.statuses (id, workflow_id, status_key, name, category, sort_order, created_at, updated_at)
VALUES ('22222222-2222-2222-2222-222222222222'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'TODO', 'To Do',
        'TODO', 10, now(), now()),
       ('33333333-3333-3333-3333-333333333333'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'IN_PROGRESS',
        'In Progress', 'IN_PROGRESS', 20, now(), now()),
       ('44444444-4444-4444-4444-444444444444'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'DONE', 'Done',
        'DONE', 30, now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO taska.transitions (id, workflow_id, from_status_id, to_status_id, name, is_hidden, sort_order, created_at,
                               updated_at)
VALUES ('55555555-5555-5555-5555-555555555555'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
        '22222222-2222-2222-2222-222222222222'::uuid, '33333333-3333-3333-3333-333333333333'::uuid,
        'Start Progress', false, 10, now(), now()),

       ('66666666-6666-6666-6666-666666666666'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
        '33333333-3333-3333-3333-333333333333'::uuid, '44444444-4444-4444-4444-444444444444'::uuid,
        'Complete', false, 20, now(), now()),

       ('77777777-7777-7777-7777-777777777777'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
        '44444444-4444-4444-4444-444444444444'::uuid, '33333333-3333-3333-3333-333333333333'::uuid,
        'Reopen', false, 30, now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO taska.workflow_bindings (project_id, issue_type, workflow_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000000'::uuid, 'TASK', '11111111-1111-1111-1111-111111111111'::uuid, now(),
        now()),
       ('00000000-0000-0000-0000-000000000000'::uuid, 'BUG', '11111111-1111-1111-1111-111111111111'::uuid, now(),
        now()),
       ('00000000-0000-0000-0000-000000000000'::uuid, 'STORY', '11111111-1111-1111-1111-111111111111'::uuid, now(),
        now())
ON CONFLICT (project_id, issue_type) DO NOTHING;