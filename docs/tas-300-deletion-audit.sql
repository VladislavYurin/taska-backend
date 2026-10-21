-- TAS-300: read-only audit against the issue-service database after deploying the fix.
-- A result is a candidate for investigation, not proof of an unauthorized deletion.
-- The original defect could also leave an authorized deletion without its events.
-- Do not restore rows automatically or manufacture history/actor information.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';

SELECT i.id, i.issue_key, i.project_id, i.deleted_at, i.version,
       EXISTS (
           SELECT 1 FROM taska.outbox_events o
           WHERE o.aggregate_id = i.id AND o.event_type = 'IssueDeleted'
       ) AS has_deleted_outbox
FROM taska.issues i
WHERE i.deleted_at IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM taska.issue_history h
      WHERE h.issue_id = i.id AND h.event_type = 'DELETED'
  )
ORDER BY i.deleted_at DESC, i.id;

COMMIT;
