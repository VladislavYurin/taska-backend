--liquibase formatted sql

-- changeset taska:0003-mask-audit-outbox-retry
-- comment: Маскировка скомпрометированных rawInviteToken и email в журнале аудита (TAS-296)

-- Обновляем старые записи, которые относятся к ретраю USER_INVITED
UPDATE taska.admin_audit_log
SET
    old_value = jsonb_set(
                        old_value::jsonb,
                        '{payload, email}',
                        '"***"'::jsonb
                ) #- '{payload, rawInviteToken}',

    new_value = jsonb_set(
                        new_value::jsonb,
                        '{payload, email}',
                        '"***"'::jsonb
                ) #- '{payload, rawInviteToken}'
WHERE
    action = 'RETRY_OUTBOX_EVENT'