# Таблицы → технические задачи

Один исполнитель схемы/исходного преобразования на таблицу; остальные карточки используют или расширяют её своим поведением. `EXISTING_DISPOSITION` включает сохранение GLOBAL/SYSTEM scope без выдуманной tenant-миграции. Для совместной таблицы новые поля/ограничения из target JSON распределяются primary ticket; consumer не переписывает применённый changeset.

Для `admin_audit_log` TAS-350 создаёт case/permit поля, dedupe/consume indexes и immutable receipt guard. TAS-482/520 зависят от этой схемы для интеграционной приёмки; каждый реализует свой workflow и не дублирует миграцию.

| Сервис / таблица | Scope | Исполнитель схемы | Использование / расширение |
| --- | --- | --- | --- |
| `admin-service.admin_audit_log` | PLATFORM_AUDIT | [TAS-350](https://jira.ozero.dev/browse/TAS-350) | TAS-482, TAS-520 |
| `auth-service.credentials` | GLOBAL | [TAS-327](https://jira.ozero.dev/browse/TAS-327) |  |
| `auth-service.invite_tokens` | GLOBAL | [TAS-327](https://jira.ozero.dev/browse/TAS-327) |  |
| `auth-service.oauth_grants` | GLOBAL | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `auth-service.oauth_states` | GLOBAL | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `auth-service.outbox_events` | GLOBAL | [TAS-318](https://jira.ozero.dev/browse/TAS-318) |  |
| `auth-service.personal_access_tokens` | GLOBAL | [TAS-463](https://jira.ozero.dev/browse/TAS-463) |  |
| `auth-service.refresh_tokens` | GLOBAL | [TAS-315](https://jira.ozero.dev/browse/TAS-315) |  |
| `auth-service.signup_rate_limits` | GLOBAL | [TAS-327](https://jira.ozero.dev/browse/TAS-327) |  |
| `auth-service.user_avatars` | GLOBAL | [TAS-366](https://jira.ozero.dev/browse/TAS-366) |  |
| `auth-service.users` | GLOBAL | [TAS-477](https://jira.ozero.dev/browse/TAS-477) | TAS-461, TAS-479, TAS-485, TAS-487, TAS-496 |
| `issue-service.idempotency_keys` | TENANT | [TAS-332](https://jira.ozero.dev/browse/TAS-332) |  |
| `issue-service.issue_attachments` | TENANT | [TAS-365](https://jira.ozero.dev/browse/TAS-365) |  |
| `issue-service.issue_comments` | TENANT | [TAS-362](https://jira.ozero.dev/browse/TAS-362) |  |
| `issue-service.issue_history` | TENANT | [TAS-343](https://jira.ozero.dev/browse/TAS-343) |  |
| `issue-service.issue_labels` | TENANT | [TAS-363](https://jira.ozero.dev/browse/TAS-363) |  |
| `issue-service.issue_links` | TENANT | [TAS-364](https://jira.ozero.dev/browse/TAS-364) |  |
| `issue-service.issue_type_move_receipts` | TENANT | [TAS-343](https://jira.ozero.dev/browse/TAS-343) |  |
| `issue-service.issue_watchers` | TENANT | [TAS-363](https://jira.ozero.dev/browse/TAS-363) |  |
| `issue-service.issue_worklogs` | TENANT | [TAS-362](https://jira.ozero.dev/browse/TAS-362) |  |
| `issue-service.issues` | TENANT | [TAS-343](https://jira.ozero.dev/browse/TAS-343) |  |
| `issue-service.outbox_events` | TENANT | [TAS-318](https://jira.ozero.dev/browse/TAS-318) |  |
| `issue-service.project_counters` | TENANT | [TAS-332](https://jira.ozero.dev/browse/TAS-332) | TAS-460 |
| `issue-service.project_labels` | TENANT | [TAS-363](https://jira.ozero.dev/browse/TAS-363) |  |
| `issue-service.workflow_binding_receipts` | TENANT | [TAS-343](https://jira.ozero.dev/browse/TAS-343) |  |
| `notification-service.email_delivery_attempts` | TENANT | [TAS-326](https://jira.ozero.dev/browse/TAS-326) |  |
| `notification-service.invitation_email_deliveries` | TENANT | [TAS-326](https://jira.ozero.dev/browse/TAS-326) |  |
| `notification-service.notification_channel_throttle` | GLOBAL | [TAS-326](https://jira.ozero.dev/browse/TAS-326) |  |
| `notification-service.notification_deliveries` | TENANT | [TAS-326](https://jira.ozero.dev/browse/TAS-326) |  |
| `notification-service.notification_preferences` | TENANT | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `notification-service.notifications` | TENANT | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `notification-service.processed_events` | TENANT | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `notification-service.telegram_link_requests` | GLOBAL | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `notification-service.telegram_updates` | GLOBAL | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `oauth-server.oauth2_authorization` | GLOBAL | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `oauth-server.oauth2_authorization_consent` | GLOBAL | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `oauth-server.oauth2_registered_client` | GLOBAL | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `oauth-server.oauth_refresh_history` | GLOBAL | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `oauth-server.oauth_registration_lock` | SYSTEM | [TAS-498](https://jira.ozero.dev/browse/TAS-498) |  |
| `project-service.issue_type_creations` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `project-service.issue_type_deletions` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `project-service.outbox_events` | TENANT | [TAS-318](https://jira.ozero.dev/browse/TAS-318) |  |
| `project-service.project_issue_types` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `project-service.project_members` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `project-service.project_settings` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `project-service.project_type_operations` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `project-service.projects` | TENANT | [TAS-329](https://jira.ozero.dev/browse/TAS-329) |  |
| `workflow-service.issue_type_creation_workflow_receipts` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.issue_type_move_workflow_pins` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.statuses` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.transitions` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.validator_rules` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.workflow_binding_changes` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.workflow_bindings` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `workflow-service.workflows` | TENANT | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `tenant-service.tenants` | TENANT | [TAS-310](https://jira.ozero.dev/browse/TAS-310) | TAS-333, TAS-337, TAS-473, TAS-478, TAS-480 |
| `tenant-service.tenant_memberships` | TENANT | [TAS-310](https://jira.ozero.dev/browse/TAS-310) | TAS-311, TAS-312, TAS-323, TAS-473, TAS-494 |
| `tenant-service.tenant_invitations` | TENANT | [TAS-335](https://jira.ozero.dev/browse/TAS-335) |  |
| `tenant-service.invitation_project_grants` | TENANT | [TAS-335](https://jira.ozero.dev/browse/TAS-335) |  |
| `tenant-service.tenant_restrictions` | TENANT | [TAS-480](https://jira.ozero.dev/browse/TAS-480) |  |
| `tenant-service.quota_counters` | TENANT | [TAS-322](https://jira.ozero.dev/browse/TAS-322) | TAS-324, TAS-347 |
| `tenant-service.quota_reservations` | TENANT | [TAS-322](https://jira.ozero.dev/browse/TAS-322) | TAS-324, TAS-347 |
| `tenant-service.quota_effects` | TENANT | [TAS-322](https://jira.ozero.dev/browse/TAS-322) | TAS-324, TAS-347 |
| `tenant-service.free_eligibility_ledger` | GLOBAL | [TAS-472](https://jira.ozero.dev/browse/TAS-472) |  |
| `tenant-service.tenant_operations` | TENANT | [TAS-337](https://jira.ozero.dev/browse/TAS-337) |  |
| `tenant-service.tenant_deletion_steps` | TENANT | [TAS-474](https://jira.ozero.dev/browse/TAS-474) |  |
| `tenant-service.tenant_exports` | TENANT | [TAS-372](https://jira.ozero.dev/browse/TAS-372) |  |
| `tenant-service.platform_configuration_versions` | GLOBAL | [TAS-462](https://jira.ozero.dev/browse/TAS-462) | TAS-518, TAS-519, TAS-520 |
| `billing-service.catalog_plans` | GLOBAL | [TAS-313](https://jira.ozero.dev/browse/TAS-313) |  |
| `billing-service.catalog_plan_versions` | GLOBAL | [TAS-313](https://jira.ozero.dev/browse/TAS-313) |  |
| `billing-service.subscriptions` | TENANT | [TAS-314](https://jira.ozero.dev/browse/TAS-314) |  |
| `billing-service.subscription_periods` | TENANT | [TAS-314](https://jira.ozero.dev/browse/TAS-314) |  |
| `billing-service.invoices` | TENANT | [TAS-475](https://jira.ozero.dev/browse/TAS-475) |  |
| `billing-service.invoice_lines` | TENANT | [TAS-475](https://jira.ozero.dev/browse/TAS-475) |  |
| `billing-service.payment_facts` | TENANT | [TAS-476](https://jira.ozero.dev/browse/TAS-476) |  |
| `billing-service.payment_allocations` | TENANT | [TAS-476](https://jira.ozero.dev/browse/TAS-476) |  |
| `billing-service.refunds` | TENANT | [TAS-358](https://jira.ozero.dev/browse/TAS-358) |  |
| `billing-service.disputes` | TENANT | [TAS-490](https://jira.ozero.dev/browse/TAS-490) |  |
| `billing-service.payment_methods` | TENANT | [TAS-349](https://jira.ozero.dev/browse/TAS-349) |  |
| `billing-service.autopay_mandates` | TENANT | [TAS-355](https://jira.ozero.dev/browse/TAS-355) |  |
| `billing-service.payment_attempts` | TENANT | [TAS-349](https://jira.ozero.dev/browse/TAS-349) |  |
| `billing-service.entitlement_snapshots` | TENANT | [TAS-319](https://jira.ozero.dev/browse/TAS-319) |  |
| `billing-service.change_notices` | TENANT | [TAS-483](https://jira.ozero.dev/browse/TAS-483) |  |
| `billing-service.settlement_cases` | TENANT | [TAS-518](https://jira.ozero.dev/browse/TAS-518) |  |
| `billing-service.unmatched_bank_receipts` | GLOBAL_FINANCIAL | [TAS-484](https://jira.ozero.dev/browse/TAS-484) | TAS-357, TAS-518 |
| `billing-service.provider_inbox` | GLOBAL_FINANCIAL | [TAS-351](https://jira.ozero.dev/browse/TAS-351) |  |
| `billing-service.fx_quotes` | GLOBAL | [TAS-519](https://jira.ozero.dev/browse/TAS-519) |  |
| `tenant-service.idempotency_records` | TENANT | [TAS-310](https://jira.ozero.dev/browse/TAS-310) |  |
| `tenant-service.outbox_events` | TENANT | [TAS-310](https://jira.ozero.dev/browse/TAS-310) | TAS-318 |
| `tenant-service.global_outbox_events` | GLOBAL | [TAS-310](https://jira.ozero.dev/browse/TAS-310) | TAS-318 |
| `tenant-service.processed_events` | TENANT | [TAS-310](https://jira.ozero.dev/browse/TAS-310) |  |
| `billing-service.idempotency_records` | TENANT | [TAS-313](https://jira.ozero.dev/browse/TAS-313) |  |
| `billing-service.outbox_events` | TENANT | [TAS-313](https://jira.ozero.dev/browse/TAS-313) | TAS-318 |
| `billing-service.global_outbox_events` | GLOBAL | [TAS-313](https://jira.ozero.dev/browse/TAS-313) | TAS-318 |
| `billing-service.processed_events` | TENANT | [TAS-313](https://jira.ozero.dev/browse/TAS-313) |  |
| `billing-service.billing_operations` | TENANT | [TAS-349](https://jira.ozero.dev/browse/TAS-349) |  |
| `auth-service.web_sessions` | GLOBAL_ACCOUNT | [TAS-315](https://jira.ozero.dev/browse/TAS-315) |  |
| `auth-service.tenant_sessions` | GLOBAL_ACCOUNT | [TAS-315](https://jira.ozero.dev/browse/TAS-315) |  |
| `auth-service.integration_grants` | GLOBAL_ACCOUNT | [TAS-463](https://jira.ozero.dev/browse/TAS-463) | TAS-498, TAS-499 |
| `auth-service.integration_grant_tenants` | GLOBAL_ACCOUNT | [TAS-499](https://jira.ozero.dev/browse/TAS-499) |  |
| `auth-service.confirmation_challenges` | GLOBAL_ACCOUNT | [TAS-328](https://jira.ozero.dev/browse/TAS-328) | TAS-373, TAS-461, TAS-479, TAS-481 |
| `auth-service.account_deletion_operations` | GLOBAL_ACCOUNT | [TAS-461](https://jira.ozero.dev/browse/TAS-461) |  |
| `auth-service.avatar_object_manifest` | GLOBAL_ACCOUNT | [TAS-366](https://jira.ozero.dev/browse/TAS-366) |  |
| `workflow-service.workflow_templates` | GLOBAL | [TAS-330](https://jira.ozero.dev/browse/TAS-330) |  |
| `project-service.resource_operation_receipts` | TENANT | [TAS-324](https://jira.ozero.dev/browse/TAS-324) |  |
| `issue-service.resource_operation_receipts` | TENANT | [TAS-365](https://jira.ozero.dev/browse/TAS-365) |  |
| `issue-service.attachment_object_manifest` | TENANT | [TAS-365](https://jira.ozero.dev/browse/TAS-365) |  |
| `notification-service.account_notification_settings` | GLOBAL_ACCOUNT | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `notification-service.account_notifications` | GLOBAL_ACCOUNT | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `notification-service.account_message_deliveries` | GLOBAL_ACCOUNT | [TAS-326](https://jira.ozero.dev/browse/TAS-326) |  |
| `notification-service.account_processed_events` | GLOBAL_ACCOUNT | [TAS-325](https://jira.ozero.dev/browse/TAS-325) |  |
| `project-service.tenant_work_partitions` | SYSTEM | [TAS-489](https://jira.ozero.dev/browse/TAS-489) |  |
| `workflow-service.tenant_work_partitions` | SYSTEM | [TAS-489](https://jira.ozero.dev/browse/TAS-489) |  |
| `issue-service.tenant_work_partitions` | SYSTEM | [TAS-489](https://jira.ozero.dev/browse/TAS-489) |  |
| `notification-service.tenant_work_partitions` | SYSTEM | [TAS-489](https://jira.ozero.dev/browse/TAS-489) |  |
| `tenant-service.tenant_work_partitions` | SYSTEM | [TAS-489](https://jira.ozero.dev/browse/TAS-489) |  |
| `billing-service.tenant_work_partitions` | SYSTEM | [TAS-489](https://jira.ozero.dev/browse/TAS-489) |  |
| `tenant-service.account_offboarding` | GLOBAL_ACCOUNT | [TAS-461](https://jira.ozero.dev/browse/TAS-461) |  |
| `tenant-service.retention_holds` | GLOBAL_CONTROL | [TAS-520](https://jira.ozero.dev/browse/TAS-520) |  |
| `billing-service.invoice_payment_matches` | TENANT | [TAS-476](https://jira.ozero.dev/browse/TAS-476) | TAS-357, TAS-484, TAS-518 |
