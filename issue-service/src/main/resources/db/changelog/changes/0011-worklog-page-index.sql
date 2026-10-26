--liquibase formatted sql

-- changeset taska:0011-worklog-page-index
-- comment: Support bounded deterministic worklog pages including timestamp ties.
CREATE INDEX issue_worklogs_issue_page_active_idx
    ON taska.issue_worklogs (issue_id, work_date DESC, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
DROP INDEX IF EXISTS taska.issue_worklogs_issue_id_work_date_active_idx;
