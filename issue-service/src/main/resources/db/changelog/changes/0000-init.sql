--liquibase formatted sql

-- changeset taska:0000-create-schema
-- comment: Создание схемы базы данных.
CREATE SCHEMA IF NOT EXISTS taska;

-- changeset taska:0001-init-extensions
-- comment: Подключение необходимого расширения.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- changeset taska:0002-create-issues
-- comment: Создание таблицы issues.
CREATE TABLE taska.issues (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid        NOT NULL,
    issue_number  int         NOT NULL,
    issue_key     text        NOT NULL,
    issue_type    text        NOT NULL,
    summary       text        NOT NULL,
    description   text        NULL,
    status_key    text        NOT NULL,
    priority      text        NOT NULL,
    assignee_id   uuid        NULL,
    reporter_id   uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       int         NOT NULL DEFAULT 1,
    deleted_at    timestamptz NULL,

    CONSTRAINT issues_issue_key_uniq UNIQUE (issue_key),
    CONSTRAINT issues_project_number_uniq UNIQUE (project_id, issue_number),
    CONSTRAINT issues_type_chk CHECK (issue_type IN ('TASK', 'BUG', 'STORY')),
    CONSTRAINT issues_status_chk CHECK (status_key IN ('TODO', 'IN_PROGRESS', 'DONE')),
    CONSTRAINT issues_priority_chk CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT issues_version_chk CHECK (version >= 1)
);

-- changeset taska:0003-issues-indexes
-- comment: Индексы для ускорения частых операций.
CREATE INDEX IF NOT EXISTS issues_project_status_idx ON taska.issues(project_id, status_key);
CREATE INDEX IF NOT EXISTS issues_project_assignee_idx ON taska.issues(project_id, assignee_id);
CREATE INDEX IF NOT EXISTS issues_project_updated_at_idx ON taska.issues(project_id, updated_at);
CREATE INDEX IF NOT EXISTS issues_status_key_idx ON taska.issues(status_key);

-- changeset taska:0004-create-project-counters
-- comment: Создание таблицы project_counters для генерации следующего номера задачи в проекте.
CREATE TABLE taska.project_counters (
    project_id        uuid  PRIMARY KEY,
    next_issue_number int   NOT NULL,

    CONSTRAINT project_counters_next_issue_number_chk CHECK (next_issue_number >= 1)
);

-- changeset taska:0005-create-issue-history
-- comment: Создание таблицы issue_history для хранения истории изменений задач.
CREATE TABLE taska.issue_history (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id      uuid        NOT NULL,
    event_type    text        NOT NULL,
    actor_user_id uuid        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT issue_history_issue_fk FOREIGN KEY (issue_id) REFERENCES taska.issues (id) ON DELETE CASCADE,
    CONSTRAINT issue_history_event_type_chk CHECK (event_type IN ('CREATED', 'UPDATED', 'ASSIGNED', 'TRANSITIONED'))
);

-- changeset taska:0006-issue-history-indexes
-- comment: Индексы для ускорения выборок истории по задаче и по пользователю.
CREATE INDEX IF NOT EXISTS issue_history_issue_occurred_idx ON taska.issue_history(issue_id, occurred_at);
CREATE INDEX IF NOT EXISTS issue_history_actor_occurred_idx ON taska.issue_history(actor_user_id, occurred_at);

-- changeset taska:0007-create-issue-links
-- comment: Создание таблицы issue_links для установления связей между задачами.
CREATE TABLE taska.issue_links (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_issue_id  uuid        NOT NULL,
    target_issue_id  uuid        NOT NULL,
    link_type        text        NOT NULL,
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT issue_links_source_fk FOREIGN KEY (source_issue_id) REFERENCES taska.issues (id) ON DELETE CASCADE,
    CONSTRAINT issue_links_target_fk FOREIGN KEY (target_issue_id) REFERENCES taska.issues (id) ON DELETE CASCADE,
    CONSTRAINT issue_links_unique UNIQUE (source_issue_id, target_issue_id, link_type),
    CONSTRAINT issue_links_link_type_chk CHECK (link_type IN ('BLOCKS', 'RELATES', 'DUPLICATES'))
);

-- changeset taska:0008-issue-links-indexes
-- comment: Индексы для ускорения поиска связей по исходной и целевой задаче.
CREATE INDEX IF NOT EXISTS issue_links_source_issue_idx ON taska.issue_links(source_issue_id);
CREATE INDEX IF NOT EXISTS issue_links_target_issue_idx ON taska.issue_links(target_issue_id);

-- changeset taska:0009-create-idempotency-keys
-- comment: Создание таблицы idempotency_keys для защиты от дублей при ретраях во время создания или изменения задачи.
CREATE TABLE taska.idempotency_keys (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key           text        NOT NULL,
    user_id       uuid        NOT NULL,
    request_hash  text        NOT NULL,
    response      jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_user_key_uniq UNIQUE (user_id, key)
);

-- changeset taska:0010-idempotency-keys-indexes
-- comment: Индекс по expires_at для эффективной очистки истёкших ключей.
CREATE INDEX IF NOT EXISTS idempotency_keys_expires_at_idx ON taska.idempotency_keys(expires_at);

-- changeset taska:0011-create-outbox-events
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

-- changeset taska:0012-outbox-events-indexes
-- comment: Индекс для выборки неопубликованных событий (воркер outbox)
CREATE INDEX IF NOT EXISTS outbox_events_unpublished_idx
    ON taska.outbox_events(created_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON taska.outbox_events(aggregate_type, aggregate_id);
