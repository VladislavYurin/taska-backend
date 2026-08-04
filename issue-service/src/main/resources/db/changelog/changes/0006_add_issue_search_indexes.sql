-- Индексы для быстрого поиска
CREATE INDEX IF NOT EXISTS idx_issues_issue_key ON taska.issues (issue_key) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_project_id ON taska.issues (project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_assignee_id ON taska.issues (assignee_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_reporter_id ON taska.issues (reporter_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_status_key ON taska.issues (status_key) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_priority ON taska.issues (priority) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_issue_type ON taska.issues (issue_type) WHERE deleted_at IS NULL;

-- GIN индекс для полнотекстового поиска по summary и description
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_issues_summary_trgm ON taska.issues USING GIN (summary gin_trgm_ops) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_issues_description_trgm ON taska.issues USING GIN (description gin_trgm_ops) WHERE deleted_at IS NULL;

-- Композитный индекс для частых запросов поиска
CREATE INDEX IF NOT EXISTS idx_issues_search_composite ON taska.issues (
                                                          project_id,
                                                          status_key,
                                                          assignee_id,
                                                          reporter_id,
                                                          priority,
                                                          issue_type
    ) WHERE deleted_at IS NULL;