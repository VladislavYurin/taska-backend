--liquibase formatted sql

-- changeset taska:0001-add-issue-comments
-- comment: Добавление таблицы комментариев к задачам

CREATE TABLE IF NOT EXISTS taska.issue_comments
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    issue_id        uuid        NOT NULL REFERENCES taska.issues (id) ON DELETE CASCADE,
    project_id      uuid        NOT NULL,
    author_user_id  uuid        NOT NULL,
    body            text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz NULL,
    version         int         NOT NULL DEFAULT 1,

    CONSTRAINT issue_comments_version_chk CHECK (version >= 1)
    );

CREATE INDEX IF NOT EXISTS issue_comments_issue_created_idx
    ON taska.issue_comments (issue_id, created_at DESC);

CREATE INDEX IF NOT EXISTS issue_comments_project_id_idx
    ON taska.issue_comments (project_id);

CREATE INDEX IF NOT EXISTS issue_comments_author_user_id_idx
    ON taska.issue_comments (author_user_id, created_at DESC)
    WHERE deleted_at IS NULL;


ALTER TABLE taska.issue_history DROP CONSTRAINT IF EXISTS issue_history_event_type_chk;

ALTER TABLE taska.issue_history ADD CONSTRAINT issue_history_event_type_chk
    CHECK (event_type IN ('CREATED', 'DELETED', 'UPDATED',
                          'ASSIGNED', 'TRANSITIONED',
                          'ATTACHMENT_UPLOADED', 'ATTACHMENT_DELETED',
                          'COMMENT_CREATED', 'COMMENT_UPDATED', 'COMMENT_DELETED'));