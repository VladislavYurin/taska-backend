--liquibase formatted sql

-- changeset taska:0002-admin-audit-log-target-service-keys
-- comment: target_service в admin_audit_log — ключ каталога admin.metadata.services, а не имя gRPC-клиента

-- Операции над пользователями (TAS-106..108) писали в target_service имя gRPC-клиента "auth-service",
-- тогда как retry outbox пишет ключ каталога ("auth"). По ключу каталога фильтруется журнал (TAS-160)
-- и подбираются правила маскирования sensitive-колонок, поэтому приводим старые записи к ключам.
UPDATE taska.admin_audit_log
SET target_service = left(target_service, length(target_service) - length('-service'))
WHERE target_service IN (
    'auth-service',
    'project-service',
    'issue-service',
    'workflow-service',
    'notification-service',
    'admin-service'
);
