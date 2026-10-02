--liquibase formatted sql

-- changeset taska:0008-add-issue-worklogs
-- comment: Добавление таблицы worklogs для time tracking и будущей аналитики.

CREATE TABLE IF NOT EXISTS taska.issue_worklogs
(
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id        uuid        NOT NULL,
    project_id      uuid        NOT NULL,
    author_user_id  uuid        NOT NULL,
    spent_minutes   int         NOT NULL,
    work_date       date        NOT NULL,
    comment         text        NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz NULL,
    version         int         NOT NULL DEFAULT 1,

    CONSTRAINT issue_worklogs_spent_minutes_chk CHECK (spent_minutes > 0),
    CONSTRAINT issue_worklogs_version_chk CHECK (version > 0)
    );

-- 1. Основной индекс для получения ворклогов задачи (ListIssueWorklogs)
CREATE INDEX IF NOT EXISTS issue_worklogs_issue_id_work_date_active_idx
    ON taska.issue_worklogs (issue_id, work_date DESC)
    WHERE deleted_at IS NULL;

-- 2. Индекс для аналитики по проекту за период (заменяет одиночный индекс по project_id)
CREATE INDEX IF NOT EXISTS issue_worklogs_project_id_work_date_active_idx
    ON taska.issue_worklogs (project_id, work_date)
    WHERE deleted_at IS NULL;

-- 3. Индекс для поиска по автору
CREATE INDEX IF NOT EXISTS issue_worklogs_author_id_active_idx
    ON taska.issue_worklogs (author_user_id)
    WHERE deleted_at IS NULL;


-- changeset taska:0008-add-issue-time-spent-minutes
-- comment: Добавление агрегированного поля time_spent_minutes в issues.

ALTER TABLE taska.issues
    ADD COLUMN IF NOT EXISTS time_spent_minutes integer NOT NULL DEFAULT 0;

ALTER TABLE taska.issues
    ADD CONSTRAINT issues_time_spent_minutes_chk CHECK (time_spent_minutes >= 0);


-- comment: Добавление событий worklog в issue_history

ALTER TABLE taska.issue_history
DROP CONSTRAINT IF EXISTS issue_history_event_type_chk;

ALTER TABLE taska.issue_history
    ADD CONSTRAINT issue_history_event_type_chk
        CHECK (event_type IN
               ('CREATED', 'UPDATED', 'ASSIGNED', 'TRANSITIONED', 'DELETED',
                'LINK_CREATED', 'LINK_DELETED',
                'ATTACHMENT_UPLOADED', 'ATTACHMENT_DELETED',
                'COMMENT_CREATED', 'COMMENT_UPDATED', 'COMMENT_DELETED',
                'LABEL_ADDED', 'LABEL_REMOVED',
                'WORKLOG_ADDED', 'WORKLOG_UPDATED', 'WORKLOG_DELETED')
            );