-- liquibase formatted sql

-- changeset taska:0000-create-schema
-- comment: Создание схемы базы данных.
CREATE SCHEMA IF NOT EXISTS taska;

-- changeset taska:0001-enable-pgcrypto
-- comment: Подключение необходимого расширения.
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- changeset taska:0002-create-workflows
-- comment: Создание таблицы workflows.
CREATE TABLE IF NOT EXISTS taska.workflows (
   id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),

   name        text NOT NULL,
   version     integer NOT NULL DEFAULT 1,
   is_active   boolean NOT NULL DEFAULT true,

   created_at  timestamptz NOT NULL DEFAULT now(),
   updated_at  timestamptz NOT NULL DEFAULT now(),

   CONSTRAINT ck_workflows_version_positive CHECK (version >= 1)
);

-- changeset taska:0003-create-statuses
-- comment: Создание таблицы statuses.
CREATE TABLE IF NOT EXISTS taska.statuses (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id  uuid NOT NULL REFERENCES taska.workflows(id) ON DELETE CASCADE,

    status_key   text NOT NULL,
    name         text NOT NULL,
    category     text NOT NULL,
    sort_order   integer NOT NULL DEFAULT 0,

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_statuses_workflow_status_key UNIQUE (workflow_id, status_key),
    CONSTRAINT ck_statuses_sort_order_nonnegative CHECK (sort_order >= 0)
);

-- changeset taska:0004-create-statuses-indexes
-- comment: Добавление индекса для ускорения поиска статусов по workflows.
CREATE INDEX IF NOT EXISTS ix_statuses_workflow_id ON taska.statuses(workflow_id);

-- changeset taska:0005-create-transitions
-- comment: Создание таблицы transitions.
CREATE TABLE IF NOT EXISTS taska.transitions (
     id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
     workflow_id     uuid NOT NULL REFERENCES taska.workflows(id) ON DELETE CASCADE,

     from_status_id  uuid NOT NULL REFERENCES taska.statuses(id) ON DELETE CASCADE,
     to_status_id    uuid NOT NULL REFERENCES taska.statuses(id) ON DELETE CASCADE,

     name            text NOT NULL,
     is_hidden       boolean NOT NULL DEFAULT false,
     sort_order      integer NOT NULL DEFAULT 0,

     created_at      timestamptz NOT NULL DEFAULT now(),
     updated_at      timestamptz NOT NULL DEFAULT now(),

     CONSTRAINT ck_transitions_from_to_different CHECK (from_status_id <> to_status_id),
     CONSTRAINT ck_transitions_sort_order_nonnegative CHECK (sort_order >= 0)
);

-- changeset taska:0006-create-transitions-indexes
-- comment: Добавление индексов для ускорения поиска переходов по workflow и статусу.
CREATE INDEX IF NOT EXISTS ix_transitions_workflow_from ON taska.transitions(workflow_id, from_status_id);
CREATE INDEX IF NOT EXISTS ix_transitions_workflow_to ON taska.transitions(workflow_id, to_status_id);

-- changeset taska:0007-create-validator-rules
-- comment: Создание таблицы validator_rules.
CREATE TABLE IF NOT EXISTS taska.validator_rules (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transition_id uuid NOT NULL REFERENCES taska.transitions(id) ON DELETE CASCADE,

    rule_type     text NOT NULL,
    config        jsonb NOT NULL DEFAULT '{}'::jsonb,

    error_code    text NULL,
    error_message text NULL,

    sort_order    integer NOT NULL DEFAULT 0,

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_validator_rules_sort_order_nonnegative CHECK (sort_order >= 0),
    CONSTRAINT uq_validator_rules_transition_type_sort UNIQUE (transition_id, rule_type, sort_order),
    CONSTRAINT ck_validator_rules_type CHECK (rule_type IN ('REQUIRE_FIELD','REQUIRE_ASSIGNEE','REQUIRE_COMMENT'))
);

-- changeset taska:0008-create-validator-rules-indexes
-- comment: Добавление индекса для ускорения поиска правил валидации по transition.
CREATE INDEX IF NOT EXISTS ix_validator_rules_transition ON taska.validator_rules(transition_id);

-- changeset taska:0009-create-workflow-bindings
-- comment: Создание таблицы привязки workflow к комбинации (project_id, issue_type).
CREATE TABLE IF NOT EXISTS taska.workflow_bindings (
    project_id  uuid NOT NULL,
    issue_type  text NOT NULL,

    workflow_id uuid NOT NULL REFERENCES taska.workflows(id) ON DELETE RESTRICT,

    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (project_id, issue_type)
);

-- changeset taska:0010-create-workflow-bindings-indexes
-- comment: Добавление индекса для ускорения поиска привязок по workflow.
CREATE INDEX IF NOT EXISTS ix_workflow_bindings_workflow_id ON taska.workflow_bindings(workflow_id);
