--liquibase formatted sql

-- changeset taska:0001-admin-audit-log
-- comment: Создание таблицы admin_audit_log

-- Admin Audit Log
CREATE TABLE IF NOT EXISTS taska.admin_audit_log
(
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id     text         NULL,
    actor_user_id  uuid         NOT NULL,
    actor_login    varchar(64)  NOT NULL,
    actor_roles    jsonb        NOT NULL,
    action         text         NOT NULL,
    target_service text         NOT NULL,
    target_table   text         NOT NULL,
    target_id      text         NOT NULL,
    old_value      jsonb        NULL,
    new_value      jsonb        NULL,
    reason         text         NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT admin_audit_reason_chk CHECK
(length(trim(reason)) > 0)
    );

CREATE INDEX IF NOT EXISTS admin_audit_log_actor_user_idx
    ON taska.admin_audit_log (actor_user_id);

CREATE INDEX IF NOT EXISTS admin_audit_log_request_idx
    ON taska.admin_audit_log (request_id);

CREATE INDEX IF NOT EXISTS admin_audit_log_created_idx
    ON taska.admin_audit_log (created_at);

CREATE INDEX IF NOT EXISTS admin_audit_log_target_idx
    ON taska.admin_audit_log (target_service, target_table, target_id);
