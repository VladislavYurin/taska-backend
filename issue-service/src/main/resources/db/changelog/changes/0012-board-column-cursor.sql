--liquibase formatted sql

-- changeset taska:0012-board-column-cursor
-- comment: Seek directly to the ascending board column cursor for each project issue type.
CREATE INDEX issues_board_column_cursor_active_idx
    ON taska.issues (project_id, issue_type, status_key, created_at ASC, id ASC)
    WHERE deleted_at IS NULL;
