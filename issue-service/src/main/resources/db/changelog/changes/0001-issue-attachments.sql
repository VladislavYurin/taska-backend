--liquibase formatted sql

-- changeset taska:0001-issue-attachments
-- comment: Таблица вложений к задачам

CREATE TABLE IF NOT EXISTS taska.issue_attachments
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    issue_id     uuid        NOT NULL,
    uploaded_by  uuid        NOT NULL,
    object_key   text        NOT NULL,
    file_name    text        NOT NULL,
    content_type text        NOT NULL,
    size_bytes   bigint      NOT NULL,
    checksum     text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    deleted_at   timestamptz NULL,

    CONSTRAINT fk_issue_attachments_issue
        FOREIGN KEY (issue_id) REFERENCES taska.issues (id)
);