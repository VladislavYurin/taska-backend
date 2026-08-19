--liquibase formatted sql

-- changeset taska:0005-board-indexes
-- comment: добавление индексов для board query: project_id + issue_type, project_id + priority.

CREATE INDEX IF NOT EXISTS issues_project_type_idx
ON taska.issues (project_id, issue_type);

CREATE INDEX IF NOT EXISTS issues_project_priority_idx
ON taska.issues (project_id, priority);