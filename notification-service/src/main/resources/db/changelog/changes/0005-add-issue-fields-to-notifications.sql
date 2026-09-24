--liquibase formatted sql

-- changeset taska:0005-add-issue-fields-to-notifications
-- comment: Замена link на структурированные поля issue_id, issue_key, project_id

ALTER TABLE taska.notifications
    ADD COLUMN IF NOT EXISTS issue_id   uuid NULL,
    ADD COLUMN IF NOT EXISTS issue_key  text NULL,
    ADD COLUMN IF NOT EXISTS project_id uuid NULL,
    DROP COLUMN IF EXISTS link;

CREATE INDEX IF NOT EXISTS notifications_issue_id_idx
    ON taska.notifications (issue_id);