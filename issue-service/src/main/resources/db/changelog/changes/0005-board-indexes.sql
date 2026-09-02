--liquibase formatted sql
-- changeset taska:0005-board-indexes
-- comment: добавление индексов для board query: project_id + issue_type, project_id + priority,
--          project_id + assignee_id, project_id + status_key.
CREATE INDEX IF NOT EXISTS issues_project_type_idx
    ON taska.issues (project_id, issue_type);
CREATE INDEX IF NOT EXISTS issues_project_priority_idx
    ON taska.issues (project_id, priority);
CREATE INDEX IF NOT EXISTS issues_project_assignee_idx
    ON taska.issues (project_id, assignee_id);
CREATE INDEX IF NOT EXISTS issues_project_status_idx
    ON taska.issues (project_id, status_key);