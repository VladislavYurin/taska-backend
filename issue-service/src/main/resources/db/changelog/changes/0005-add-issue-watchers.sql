--liquibase formatted sql

-- changeset taska:0005-add-issue-watchers
-- comment: Добавление таблицы подписчиков на задачи

CREATE TABLE IF NOT EXISTS taska.issue_watchers
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    issue_id   uuid        NOT NULL REFERENCES taska.issues (id) ON DELETE CASCADE,
    project_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid        NOT NULL,

    CONSTRAINT issue_watchers_issue_user_uniq UNIQUE (issue_id, user_id)
);

CREATE INDEX IF NOT EXISTS issue_watchers_project_id_idx
    ON taska.issue_watchers (project_id);

CREATE INDEX IF NOT EXISTS issue_watchers_user_id_idx
    ON taska.issue_watchers (user_id);
