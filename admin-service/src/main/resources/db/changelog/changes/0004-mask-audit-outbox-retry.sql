--liquibase formatted sql

-- changeset taska:0004-mask-audit-outbox-retry
-- comment: Маскировка скомпрометированных rawInviteToken и email в журнале аудита (TAS-296)

-- Обновляем старые записи, которые относятся к ретраю USER_INVITED
UPDATE taska.admin_audit_log
SET
    old_value = old_value::jsonb #- '{payload, rawInviteToken}',
    new_value = new_value::jsonb #- '{payload, rawInviteToken}'
WHERE
    action = 'RETRY_OUTBOX_EVENT'
  AND target_service IN ('auth', 'auth-service')
  AND jsonb_typeof(old_value::jsonb) = 'object'
  AND jsonb_typeof(old_value::jsonb->'payload') = 'object'
  AND jsonb_exists(old_value::jsonb->'payload', 'rawInviteToken');

UPDATE taska.admin_audit_log
SET
    old_value = jsonb_set(old_value::jsonb, '{payload, email}', '"***"'::jsonb),
    new_value = jsonb_set(new_value::jsonb, '{payload, email}', '"***"'::jsonb)
WHERE
    action = 'RETRY_OUTBOX_EVENT'
  AND target_service IN ('auth', 'auth-service')
  AND jsonb_typeof(old_value::jsonb) = 'object'
  AND jsonb_typeof(old_value::jsonb->'payload') = 'object'
  AND jsonb_exists(old_value::jsonb->'payload', 'email');