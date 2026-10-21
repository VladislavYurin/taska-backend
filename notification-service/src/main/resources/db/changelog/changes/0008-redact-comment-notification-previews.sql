--liquibase formatted sql

-- changeset taska:0008-redact-comment-notification-previews
-- comment: Remove stored comment previews from inbox and pending email content.

UPDATE taska.notifications
SET body = CASE
    WHEN NULLIF(btrim(issue_key), '') IS NOT NULL THEN 'Новый комментарий к задаче ' || issue_key
    WHEN issue_id IS NOT NULL THEN 'Новый комментарий к задаче ' || issue_id::text
    ELSE 'Новый комментарий'
END
WHERE notification_type = 'ISSUE_COMMENT_CREATED';
