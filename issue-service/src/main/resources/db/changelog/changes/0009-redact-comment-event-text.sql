--liquibase formatted sql

-- changeset taska:0009-redact-comment-event-text
-- comment: Comment text belongs only to issue_comments, not history or durable events.

UPDATE taska.issue_history
SET payload = payload - 'body' - 'oldBody' - 'newBody'
WHERE event_type IN ('COMMENT_CREATED', 'COMMENT_UPDATED', 'COMMENT_DELETED')
  AND jsonb_typeof(payload) = 'object'
  AND jsonb_exists_any(payload, ARRAY['body', 'oldBody', 'newBody']);

UPDATE taska.outbox_events
SET payload = payload - 'body' - 'oldBody' - 'newBody'
WHERE event_type IN ('CommentCreated', 'CommentUpdated', 'CommentDeleted')
  AND jsonb_typeof(payload) = 'object'
  AND jsonb_exists_any(payload, ARRAY['body', 'oldBody', 'newBody']);
