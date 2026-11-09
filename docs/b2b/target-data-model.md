# Целевая модель данных Taska B2B — TAS-303

**DESIGNED, не реализовано и не развёрнуто.** Baseline `af7ab57ead44304b89bbd47dabeeba45b761ce16`; бизнес-источник Confluence 60751880 v42, D01–D253. [Текущий реестр](data-inventory.md) содержит всю фактическую SQL-схему. [Полный target JSON](schemas/target-model.json) задаёт итоговые колонки/ограничения/индексы для **55 существующих и 65 новых таблиц**. Этот документ описывает решения и полный каталог новых сущностей; JSON нормативен для точных per-table полей/DDL.

## Принятые технические решения

- UUID — неизменяемая identity. Tenant/project IDs не меняются и проекты не перемещаются между компаниями. `tenant_id` — UUID NOT NULL у tenant-owned строки. Существующие UUID PK сохраняются; UNIQUE `(tenant_id,id)`/`(tenant_id,operation_id)` служат целью составных FK. Для естественных составных ключей tenant включается в PK.
- Локальные FK tenant-таблиц включают `tenant_id` с обеих сторон. Дополнительные project/workflow ключи исключают несогласованные дочерние объекты. Между БД FK нет: RPC проверяет authoritative owner, projection несёт версию, операция хранит durable receipt.
- На tenant-owned таблицах включаются ENABLE + FORCE RLS. SQL всегда содержит tenant predicate; RLS — второй рубеж. Неизвестный tenant не превращается в глобальный контекст. Исключения перечислены per table, а не выводятся из NULL.
- Все B-tree business indexes/uniques tenant-owned таблиц получают ведущий `tenant_id`; глобальный UUID PK остаётся глобальным. GIN поиска остаётся вместе с отдельным tenant B-tree; нельзя механически добавить UUID в GIN без другого opclass/extension. EXPLAIN с реальными объёмами остаётся проверкой реализации.
- Денежные суммы — BIGINT minor units, ISO currency CHAR(3), exponent SMALLINT 0..3; wire только EUR. API передаёт `amountMinor` строкой. FX NUMERIC(20,10), HALF_UP один раз до minor units при выпуске invoice. Суммы/FX/terms snapshots неизменяемы; verified money facts и распределения — разные таблицы.
- Время — UTC TIMESTAMPTZ; оплаченные интервалы `[starts_at,ends_at)`. День anchor хранится отдельно 1..31 и восстанавливается после короткого месяца. Period status GRANTED/REVOKED/SUPERSEDED; будущее/действующее/истёкшее вычисляется по часам, не scheduler.
- `TENANT_ADMIN`/membership живут в tenant-service. Identity/displayName/avatar глобальны в auth-service. Owner защищён составным отложенным FK на membership; роль ACTIVE TENANT_ADMIN и последний администратор проверяются под блокировкой строки компании.
- Durable операции, outbox, quota receipts и provider dedupe переживают retries. Response cache TTL 24h — техническое хранение ответа, не срок забывания финансового эффекта. Истечение TTL не разрешает повторный payment/refund/usage effect.
- Платформенные флаги versioned и глобальны; индивидуальных overrides нет. Название — поле `tenants.name`, отдельная произвольная таблица tenant settings не вводится.
- Общие workflow templates отделены от изменяемых runtime workflows; при создании копируются в tenant/project с новыми IDs.
- Scope grants: USER = ONE_TENANT, ADMIN = TENANT_SET либо PLATFORM; READ/MODIFY плюс allowlist operations. PLATFORM нужен для глобальных account mutations. OAuth AS хранит ссылку на единый authoritative auth grant, а не второй независимый источник прав.
- Коды подтверждения — восемь цифр, 10 минут absolute TTL, пять попыток; повторная доставка не чаще 60 секунд, максимум три и не продлевает TTL. Purpose/target/version различаются, ADMIN_LOGIN только email. Это технический ADR, не новое бизнес-требование MFA.

## Полный disposition существующих таблиц

Все остальные поля сохраняют тип/nullability/default, явно перечисленные в JSON. Ни одна исходная таблица не исключена по отсутствию JPA/R2DBC entity.

| Таблица | Target scope | Изменения сверх tenant/composite/index policy |
|---|---|---|
| `admin-service.admin_audit_log` | PLATFORM_AUDIT | Add optional target tenant; audit remains platform-only and is never exposed to tenant roles. |
| `auth-service.credentials` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `auth-service.invite_tokens` | GLOBAL | Retain only global registration/password onboarding purposes; company invitations move to tenant-service. Add purpose check to prevent token confusion. |
| `auth-service.oauth_grants` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `auth-service.oauth_states` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `auth-service.outbox_events` | GLOBAL | Auth outbox remains GLOBAL_ACCOUNT only; project/issue outboxes are TENANT only. Scope kind is constrained to its owner and cannot claim a foreign tenant. |
| `auth-service.personal_access_tokens` | GLOBAL | Token is global credential whose authoritative grant selects tenant(s); no untrusted tenant header can expand grant. |
| `auth-service.refresh_tokens` | GLOBAL | Bind refresh family to web_sessions; tenant contexts have separate tenant_sessions IDs and cannot switch each other. |
| `auth-service.signup_rate_limits` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `auth-service.user_avatars` | GLOBAL | Keep global ownership and 2 MB cap; no tenant_id. S3 lifecycle is durable via avatar_object_manifest. |
| `auth-service.users` | GLOBAL | After confirmed deletion expose tombstone identity; purge/anonymization follows accepted D253/ES_V1 and applicable scoped legal holds. Missing/corrupt policy blocks physical purge only, never restores access. No reuse of user UUID. AdminAccount.version is the decimal-string representation of account_version (bigint NOT NULL DEFAULT 1, CHECK > 0). It is the control aggregate version for global_role/status/deleted_at, distinct from profile_version and auth_version. Under the auth user-row lock compare the expected account version, recheck authority and state, then increment once in the same transaction for each actual role/state/logical-deletion transition, including invitation acceptance, login lock/unlock and bootstrap/recovery when those flows change existing control fields. Account creation starts at version 1. Idempotent replay/no-op does not increment; profile-only updates do not substitute this clock. Schema migration belongs to TAS-477; administrative transition logic and GET mapping belong to TAS-485, deletion transition consumer TAS-487. A retained deletion tombstone exposes no historical profile PII; after lawful physical purge administrative GET returns 404. |
| `issue-service.idempotency_keys` | TENANT | Replace (user_id,key) uniqueness with (tenant_id,user_id,operation_code,key); hash includes canonical target IDs and payload. Financial effects additionally have permanent operation identity. |
| `issue-service.issue_attachments` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issue_comments` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issue_history` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issue_labels` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issue_links` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issue_type_move_receipts` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `issue-service.issue_watchers` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issue_worklogs` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.issues` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.outbox_events` | TENANT | Auth outbox remains GLOBAL_ACCOUNT only; project/issue outboxes are TENANT only. Scope kind is constrained to its owner and cannot claim a foreign tenant. |
| `issue-service.project_counters` | TENANT | next_issue_number is display numbering only. lifetime_created is authoritative TASK_LIFETIME usage, incremented atomically with issue + idempotency receipt/outbox in the same issue-service transaction on FREE and paid. No remote reservation. Never derive successful creation count from max number or current rows. Deleting an issue never decrements lifetime_created. |
| `issue-service.project_labels` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `issue-service.workflow_binding_receipts` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `notification-service.email_delivery_attempts` | TENANT | Only tenant-origin work/mandatory billing messages; global account security uses account_notifications/account_message_deliveries. Resolve current recipient/ACL again before sending. |
| `notification-service.invitation_email_deliveries` | TENANT | PK is delivery UUID, not recipient user; multiple companies/invitations coexist. secret_ref references protected short-lived delivery secret; never Kafka plaintext. Global signup/security uses account_message_deliveries. |
| `notification-service.notification_channel_throttle` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `notification-service.notification_deliveries` | TENANT | Only tenant-origin work/mandatory billing messages; global account security uses account_notifications/account_message_deliveries. Resolve current recipient/ACL again before sending. |
| `notification-service.notification_preferences` | TENANT | Task delivery opt-ins belong to (tenant_id,user_id). Move global contact/Telegram binding/version to account_notification_settings; do not clone Telegram secrets per tenant. Mandatory messages bypass ordinary opt-outs. |
| `notification-service.notifications` | TENANT | Only tenant-origin work/mandatory billing messages; global account security uses account_notifications/account_message_deliveries. Resolve current recipient/ACL again before sending. |
| `notification-service.processed_events` | TENANT | Tenant scope enforced in uniqueness; global dedupe uses account_processed_events. |
| `notification-service.telegram_link_requests` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `notification-service.telegram_updates` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `oauth-server.oauth2_authorization` | GLOBAL | Authoritative grant is auth-service.integration_grants; cross-database link is verified by RPC, never SQL FK. Consent primary key includes integration_grant_id, so tenant A consent never overwrites tenant B. |
| `oauth-server.oauth2_authorization_consent` | GLOBAL | Authoritative grant is auth-service.integration_grants; cross-database link is verified by RPC, never SQL FK. Consent primary key includes integration_grant_id, so tenant A consent never overwrites tenant B. |
| `oauth-server.oauth2_registered_client` | GLOBAL | Полная исходная структура сохранена; применяется общая политика scope. |
| `oauth-server.oauth_refresh_history` | GLOBAL | Grant scope inherited from immutable authorization -> integration_grant_id; token refresh cannot widen scope. |
| `oauth-server.oauth_registration_lock` | SYSTEM | Полная исходная структура сохранена; применяется общая политика scope. |
| `project-service.issue_type_creations` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `project-service.issue_type_deletions` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `project-service.outbox_events` | TENANT | Auth outbox remains GLOBAL_ACCOUNT only; project/issue outboxes are TENANT only. Scope kind is constrained to its owner and cannot claim a foreign tenant. |
| `project-service.project_issue_types` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `project-service.project_members` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `project-service.project_settings` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `project-service.project_type_operations` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `project-service.projects` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `workflow-service.issue_type_creation_workflow_receipts` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `workflow-service.issue_type_move_workflow_pins` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `workflow-service.statuses` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `workflow-service.transitions` | TENANT | Both endpoints constrained to same tenant AND workflow, not merely existing status IDs. |
| `workflow-service.validator_rules` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `workflow-service.workflow_binding_changes` | TENANT | Retain verified original actor/grant/version and operation fence for recovery; jobs do not manufacture an administrator context or ignore revoked membership. |
| `workflow-service.workflow_bindings` | TENANT | Полная исходная структура сохранена; применяется общая политика scope. |
| `workflow-service.workflows` | TENANT | All runtime workflows belong to one tenant/project; global templates move to workflow_templates, copied with new workflow/status/transition UUIDs. Shared mutable default workflows are prohibited. |

### Версия административного состояния аккаунта

В target `auth-service.taska.users` добавляется `account_version bigint NOT NULL DEFAULT 1`, ограничение `users_account_version_positive_chk: CHECK (account_version > 0)`. `AdminAccount.version` передаёт его десятичной строкой; version из GET используется для последующих административных If-Match/expectedAccountVersion. Это отдельная версия управляющего состояния, не `profile_version` и не `auth_version`.

Изменение global_role/status/deleted_at выполняется под блокировкой строки пользователя: повторно проверить полномочия и текущую account_version, применить переход и увеличить account_version ровно один раз в той же транзакции. Все writers этих полей используют общий version protocol, включая принятие приглашения, login lock/unlock и bootstrap/recovery при изменении управляющих полей существующего аккаунта; новый аккаунт начинает с версии 1. retry уже выполненной операции и фактический no-op версию не увеличивают. Security revocation изменяет auth_version отдельно по своим правилам. Миграция users — TAS-477; административные GET/переходы — TAS-485, удаление — TAS-487. Retained tombstone не раскрывает прежние профильные PII; после допустимого физического purge административный GET возвращает 404.

## Новые таблицы: полные поля и ограничения

Обозначения: все поля без `NULL` обязательны. UUID генерирует владелец операции до первого запроса; повтор использует тот же ID. Для каждого локального FK перечислены обе стороны. Все JSONB payloads должны соответствовать versioned DTO/operation schema контракта; произвольные secrets/private content в них запрещены.

### tenant-service · taska.tenants

Scope `TENANT`; retention `TENANT_WORKDATA`. RLS compares id to context tenant, not a duplicate tenant_id; owner_id is global auth UUID (logical FK). creation deadline immutable once assigned; no tenant timezone/logo/settings map. owner_version increases only on ownership transfer; unrelated name/limit/membership changes do not invalidate a mandate. TAS-310 stores immutable original actor/request SHA-256/plan/operation and derives UUIDv8 from actor+idempotency key; durable creation identity survives response-cache expiry. [Implementation and limits](../../tenant-service/README.md).

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `name` `varchar(128)` NOT NULL
- `owner_id` `uuid` NOT NULL
- `lifecycle` `text` NOT NULL
- `creation_kind` `text` NOT NULL
- `creation_deadline_at` `timestamptz` NULL
- `first_payment_fact_id` `uuid` NULL
- `version` `bigint` NOT NULL DEFAULT `1`
- `membership_version` `bigint` NOT NULL DEFAULT `1`
- `restriction_version` `bigint` NOT NULL DEFAULT `1`
- `created_at` `timestamptz` NOT NULL DEFAULT `now()`
- `updated_at` `timestamptz` NOT NULL DEFAULT `now()`
- `deleted_at` `timestamptz` NULL
- `owner_version` `bigint` NOT NULL DEFAULT `1`
- `plan_version_id` `uuid` NOT NULL
- `creation_actor_id` `uuid` NOT NULL
- `creation_request_hash` `bytea` NOT NULL
- `creation_operation_id` `uuid` NOT NULL

PK `(id)`.

Локальные FK:

- `(id, owner_id) → tenant_memberships(tenant_id, user_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED`.

CHECK:

- `lifecycle IN ('PROVISIONING','AWAITING_PAYMENT','ACTIVE','AUTO_CLOSED','OWNER_CANCELLED','DELETING','DELETED')`.
- `creation_kind IN ('FREE','PAID')`.
- `version>0 AND membership_version>0 AND restriction_version>0`.
- `owner_version>0`.

Дополнительные indexes:

- `(owner_id,id)`.
- `(creation_deadline_at,id) WHERE lifecycle='AWAITING_PAYMENT'`.

### tenant-service · taska.tenant_memberships

Scope `TENANT`; retention `TENANT_WORKDATA`. Removed membership is tombstone; rejoin increments version. Owner and last active admin protected by tenant row lock, not partial UNIQUE. SUSPENDED consumes seat.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `role` `text` NOT NULL
- `status` `text` NOT NULL
- `version` `bigint` NOT NULL DEFAULT `1`
- `joined_at` `timestamptz` NOT NULL DEFAULT `now()`
- `suspended_at` `timestamptz` NULL
- `removed_at` `timestamptz` NULL

PK `(tenant_id, user_id)`.

Локальные FK:

- `(tenant_id) → tenants(id) ON DELETE RESTRICT`.

CHECK:

- `role IN ('TENANT_ADMIN','MEMBER')`.
- `status IN ('ACTIVE','SUSPENDED','REMOVED')`.
- `version>0`.

Дополнительные indexes:

- `(user_id,status,tenant_id)`.
- `(tenant_id,role,status,user_id)`.

### tenant-service · taska.tenant_invitations

Scope `TENANT`; retention `TENANT_WORKDATA`. TTL snapshot default 7 days; hashing only, email canonicalization same as auth. Expiry process changes state before a replacement reservation is accepted.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `email_canonical` `varchar(320)` NOT NULL
- `inviter_id` `uuid` NOT NULL
- `member_role` `text` NOT NULL
- `token_hash` `bytea` NOT NULL
- `state` `text` NOT NULL
- `reservation_id` `uuid` NOT NULL
- `issued_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NOT NULL
- `accepted_by` `uuid` NULL
- `accepted_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(token_hash)`.

Локальные FK:

- `(tenant_id) → tenants(id) ON DELETE RESTRICT`.
- `(tenant_id, reservation_id) → quota_reservations(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('PENDING','ACCEPTED','REVOKED','EXPIRED')`.
- `member_role IN ('TENANT_ADMIN','MEMBER')`.
- `expires_at>issued_at`.

Дополнительные indexes:

- `UNIQUE (tenant_id,email_canonical) WHERE state='PENDING'`.
- `(tenant_id,inviter_id,state)`.
- `(tenant_id,expires_at,id) WHERE state='PENDING'`.

### tenant-service · taska.invitation_project_grants

Scope `TENANT`; retention `TENANT_WORKDATA`. Project ID cross-service logical FK checked at issue and acceptance. Skipped grants never roll back accepted membership.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `invitation_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `project_role` `text` NOT NULL
- `outcome` `text` NULL
- `outcome_reason` `text` NULL

PK `(tenant_id, invitation_id, project_id)`.

Локальные FK:

- `(tenant_id, invitation_id) → tenant_invitations(tenant_id, id) ON DELETE CASCADE`.

CHECK:

- `project_role IN ('ADMIN','MEMBER','VIEWER')`.
- `outcome IS NULL OR outcome IN ('APPLIED','SKIPPED')`.

### tenant-service · taska.tenant_restrictions

Scope `TENANT`; retention `TENANT_WORKDATA`. Each source remains independent; clearing one dispute does not clear another or pause/block. Billing source event monotonic version guards stale delivery.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `kind` `text` NOT NULL
- `source_id` `uuid` NOT NULL
- `active` `boolean` NOT NULL
- `version` `bigint` NOT NULL
- `reason_code` `text` NOT NULL
- `created_by` `uuid` NULL
- `created_at` `timestamptz` NOT NULL
- `resolved_at` `timestamptz` NULL

PK `(tenant_id, kind, source_id)`.

Локальные FK:

- `(tenant_id) → tenants(id) ON DELETE RESTRICT`.

CHECK:

- `kind IN ('OWNER_PAUSE','PLATFORM_BLOCK','NONPAYMENT','QUOTA_EXCEEDED','FINANCIAL_DISPUTE')`.

Дополнительные indexes:

- `(tenant_id,active,kind)`.

### tenant-service · taska.quota_counters

Scope `TENANT`; retention `TENANT_WORKDATA`. scope_id equals tenant UUID for all central resources; TASK_LIFETIME is authoritative in issue-service.project_counters.lifetime_created. No nullable/sentinel scope. Row locked atomic admission; limit sourced from versioned entitlement. BIGINT safe arithmetic checked.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `resource` `text` NOT NULL
- `scope_id` `uuid` NOT NULL
- `used` `bigint` NOT NULL DEFAULT `0`
- `reserved` `bigint` NOT NULL DEFAULT `0`
- `version` `bigint` NOT NULL DEFAULT `1`
- `reconciled_at` `timestamptz` NULL

PK `(tenant_id, resource, scope_id)`.

Локальные FK:

- `(tenant_id) → tenants(id) ON DELETE RESTRICT`.

CHECK:

- `resource IN ('SEAT','PROJECT','STORAGE_BYTES')`.
- `used>=0 AND reserved>=0 AND version>0`.
- `scope_id=tenant_id`.

### tenant-service · taska.quota_reservations

Scope `TENANT`; retention `TENANT_WORKDATA`. Expiration is eligible-to-reconcile, not proof no resource exists. Query durable owner receipt before release; lease/fence forbids later commit of expired generation. COMMITTED storage remains used until confirmed deletion receipt.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `resource` `text` NOT NULL
- `scope_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `resource_owner` `text` NOT NULL
- `fence` `bigint` NOT NULL DEFAULT `1`
- `resource_id` `uuid` NULL
- `amount` `bigint` NOT NULL
- `state` `text` NOT NULL
- `entitlement_version` `bigint` NOT NULL
- `expires_at` `timestamptz` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `committed_at` `timestamptz` NULL
- `released_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, operation_id, resource)`.

Локальные FK:

- `(tenant_id, resource, scope_id) → quota_counters(tenant_id, resource, scope_id) ON DELETE RESTRICT`.

CHECK:

- `amount>0`.
- `state IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED')`.

Дополнительные indexes:

- `(tenant_id,expires_at,id) WHERE state='ACTIVE'`.

### tenant-service · taska.quota_effects

Scope `TENANT`; retention `TENANT_WORKDATA`. Permanent replay fence for applied resource effects. TASK_LIFETIME is not reserved or released here; tenant-service only receives a read projection.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `resource` `text` NOT NULL
- `scope_id` `uuid` NOT NULL
- `effect_kind` `text` NOT NULL
- `amount` `bigint` NOT NULL
- `resource_id` `uuid` NOT NULL
- `owner_version` `bigint` NOT NULL
- `applied_at` `timestamptz` NOT NULL

PK `(tenant_id, operation_id, resource, effect_kind)`.

Локальные FK:

- `(tenant_id, resource, scope_id) → quota_counters(tenant_id, resource, scope_id) ON DELETE RESTRICT`.

CHECK:

- `effect_kind IN ('COMMIT','RELEASE_USAGE')`.
- `amount>0`.

### tenant-service · taska.free_eligibility_ledger

Scope `GLOBAL`; retention `FREE_ELIGIBILITY`. ES_V1 FREE_ELIGIBILITY: HMAC-SHA256 of canonical verified email under dedicated KMS key, no fingerprinting or cross-email inference. purpose_version and review_due_at freeze approved purpose assessment; annual review does not extend on lookup. Same live company FREE-paid-FREE keeps tenant binding. Paid owner transfer never rewrites/releases the previous identity marker: check current owner marker on later FREE selection (same company allow; unused consume; other company deny). Multiple identity markers may bind the same tenant: NO UNIQUE(bound_tenant_id). Account deletion may clear first_user_id while live tenant binding remains. Tenant deletion clears bound_tenant_id, first_user_id and operation_id on ALL matching rows; only digest/key version/consumed_at(published usedAt)/purpose_version/review_due_at remain. Upheld erasure or purpose end removes active matching; never reconstruct from backups/financial archives; overdue purpose review denies new FREE admission as POLICY_NOT_CONFIGURED. A legally blocked copy, if required by separately evidenced disposition, is outside this matching table and cannot be queried for FREE decisions.

ES_V1 mapping: `FREE_ELIGIBILITY` / `free_live_company_binding`; field/purpose overrides — target JSON.

Поля:

- `email_digest` `bytea` NOT NULL
- `digest_key_version` `integer` NOT NULL
- `first_user_id` `uuid` NULL
- `bound_tenant_id` `uuid` NULL
- `consumed_at` `timestamptz` NOT NULL
- `operation_id` `uuid` NULL
- `purpose_version` `text` NOT NULL
- `review_due_at` `timestamptz` NOT NULL

PK `(digest_key_version, email_digest)`.

UNIQUE: `(operation_id)`.

CHECK:

- `bound_tenant_id IS NOT NULL OR (first_user_id IS NULL AND operation_id IS NULL)`.
- `review_due_at>consumed_at`.

Дополнительные indexes:

- `(bound_tenant_id) WHERE bound_tenant_id IS NOT NULL`.
- `(review_due_at,digest_key_version)`.

### tenant-service · taska.tenant_operations

Scope `TENANT`; retention `TENANT_WORKDATA`. Payload schema keyed by kind in state-machine doc; no credentials or private message bodies. Original actor/grant retained; job revalidates current authority when operation requires it.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `kind` `text` NOT NULL
- `actor_id` `uuid` NOT NULL
- `channel` `text` NOT NULL
- `grant_id` `uuid` NULL
- `request_key` `varchar(128)` NOT NULL
- `request_hash` `bytea` NOT NULL
- `state` `text` NOT NULL
- `fence` `bigint` NOT NULL DEFAULT `1`
- `payload` `jsonb` NOT NULL
- `result` `jsonb` NULL
- `created_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, actor_id, kind, request_key)`.

Локальные FK:

- `(tenant_id) → tenants(id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('ACCEPTED','RUNNING','SUCCEEDED','FAILED','COMPENSATING','CANCELLED')`.
- `fence>0`.

Дополнительные indexes:

- `(tenant_id,updated_at,id) WHERE state IN ('ACCEPTED','RUNNING','COMPENSATING')`.

### tenant-service · taska.tenant_deletion_steps

Scope `TENANT`; retention `TENANT_WORKDATA`. Retry/checkpoint per owner under accepted D253/ES_V1. Execute due cleanup when policy and fiscal applicability are valid; active scoped legal hold or missing/corrupt required policy/applicability yields HELD only for the affected step. Preserve tombstone and revoked access. Failure is not successful deletion of physical data.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `service` `text` NOT NULL
- `step` `text` NOT NULL
- `state` `text` NOT NULL
- `cursor` `text` NULL
- `lease_id` `uuid` NULL
- `lease_expires_at` `timestamptz` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `last_error_code` `text` NULL
- `retention_policy_version` `bigint` NULL
- `retention_hold_version` `bigint` NULL
- `retention_snapshot` `jsonb` NULL
- `updated_at` `timestamptz` NOT NULL

PK `(tenant_id, operation_id, service, step)`.

Локальные FK:

- `(tenant_id, operation_id) → tenant_operations(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('PENDING','RUNNING','SUCCEEDED','RETRYABLE_FAILURE','HELD')`.
- `attempts>=0`.

### tenant-service · taska.tenant_exports

Scope `TENANT`; retention `TENANT_WORKDATA`. Authorize snapshot generation and download with current ACL; financial and credential material excluded. Artifact expiry 24h technical default; irreversible tenant financial data retention separate O09.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_workfiles`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `actor_id` `uuid` NOT NULL
- `grant_id` `uuid` NULL
- `operation_id` `uuid` NOT NULL
- `scope_snapshot` `jsonb` NOT NULL
- `acl_version` `bigint` NOT NULL
- `state` `text` NOT NULL
- `object_key` `text` NULL
- `size_bytes` `bigint` NULL
- `checksum` `text` NULL
- `expires_at` `timestamptz` NULL
- `created_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, operation_id)`.

Локальные FK:

- `(tenant_id) → tenants(id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('QUEUED','RUNNING','READY','FAILED','EXPIRED','DELETING','DELETED')`.
- `size_bytes IS NULL OR size_bytes>=0`.

Дополнительные indexes:

- `(tenant_id,actor_id,created_at,id)`.

### tenant-service · taska.platform_configuration_versions

Scope `GLOBAL`; retention `PLATFORM_CONFIGURATION`. Only explicit registry keys, typed value validation. JSON only for versioned MERCHANT_CONFIGURATION, RETENTION_POLICY, SETTLEMENT_POLICY schemas with readiness, unresolved decision IDs and secret refs; unset critical values never enable final actions. No tenant overrides. See root registry for defaults/ranges.

ES_V1 mapping: `NON_PERSONAL_CONFIGURATION` / `active/referenced configuration`; field/purpose overrides — target JSON.

Поля:

- `key` `text` NOT NULL
- `version` `bigint` NOT NULL
- `value_type` `text` NOT NULL
- `value` `jsonb` NOT NULL
- `effective_at` `timestamptz` NOT NULL
- `changed_by` `uuid` NOT NULL
- `created_at` `timestamptz` NOT NULL

PK `(key, version)`.

CHECK:

- `value_type IN ('BOOLEAN','INTEGER','DURATION_SECONDS','DECIMAL','STRING','JSON')`.
- `version>0`.

Дополнительные indexes:

- `(key,effective_at DESC,version DESC)`.

### billing-service · taska.catalog_plans

Scope `GLOBAL`; retention `CATALOG_HISTORY`. Stable product identity; no deletion referenced by snapshots.

ES_V1 mapping: `NON_PERSONAL_CONFIGURATION` / `active/referenced configuration`; field/purpose overrides — target JSON.

Поля:

- `plan_code` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `archived_at` `timestamptz` NULL

PK `(plan_code)`.

### billing-service · taska.catalog_plan_versions

Scope `GLOBAL`; retention `CATALOG_HISTORY`. Immutable after publication; NULL max_tasks means unlimited paid task cap, usage still counted. Fixed company price, not seats.

ES_V1 mapping: `NON_PERSONAL_CONFIGURATION` / `active/referenced configuration`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `plan_code` `text` NOT NULL
- `version` `bigint` NOT NULL
- `name` `text` NOT NULL
- `price_minor` `bigint` NOT NULL
- `currency` `char(3)` NOT NULL
- `currency_exponent` `smallint` NOT NULL
- `max_projects` `bigint` NOT NULL
- `max_members` `bigint` NOT NULL
- `max_storage_bytes` `bigint` NOT NULL
- `max_tasks_per_project` `bigint` NULL
- `max_attachment_bytes` `bigint` NOT NULL
- `features` `jsonb` NOT NULL
- `published_at` `timestamptz` NULL
- `created_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(plan_code, version)`.

Локальные FK:

- `(plan_code) → catalog_plans(plan_code) ON DELETE RESTRICT`.

CHECK:

- `price_minor>=0 AND currency_exponent BETWEEN 0 AND 3`.
- `max_projects>0 AND max_members>0 AND max_storage_bytes>=0 AND max_attachment_bytes>0`.
- `max_tasks_per_project IS NULL OR max_tasks_per_project>0`.

### billing-service · taska.subscriptions

Scope `TENANT`; retention `FINANCIAL_RECORD`. Current entitlement pointer is not full payment history. No implicit FREE fallback. Calendar retains original day through short months.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `selected_plan_version_id` `uuid` NOT NULL
- `current_period_id` `uuid` NULL
- `renewal_enabled` `boolean` NOT NULL
- `next_plan_version_id` `uuid` NULL
- `anchor_day` `smallint` NULL
- `anchor_time_utc` `time` NULL
- `version` `bigint` NOT NULL
- `updated_at` `timestamptz` NOT NULL

PK `(tenant_id)`.

Локальные FK:

- `(selected_plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.
- `(next_plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.
- `(tenant_id, current_period_id) → subscription_periods(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `anchor_day IS NULL OR anchor_day BETWEEN 1 AND 31`.
- `version>0`.

### billing-service · taska.subscription_periods

Scope `TENANT`; retention `FINANCIAL_RECORD`. Period immutable times/terms. Status is GRANTED/REVOKED/SUPERSEDED; future/active/expired are computed from UTC timestamps, never scheduler freshness. Historical overlapping paid periods allowed on immediate downgrade; entitlement selects current non-superseded coverage. Do not EXCLUDE all overlap.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `invoice_id` `uuid` NOT NULL
- `plan_version_id` `uuid` NOT NULL
- `starts_at` `timestamptz` NOT NULL
- `ends_at` `timestamptz` NOT NULL
- `anchor_day` `smallint` NOT NULL
- `anchor_time_utc` `time` NOT NULL
- `status` `text` NOT NULL
- `activation_operation_id` `uuid` NOT NULL
- `terms_snapshot` `jsonb` NOT NULL
- `created_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, invoice_id)`; `(tenant_id, activation_operation_id)`.

Локальные FK:

- `(plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.
- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `ends_at>starts_at AND anchor_day BETWEEN 1 AND 31`.
- `status IN ('GRANTED','REVOKED','SUPERSEDED')`.

Дополнительные indexes:

- `(tenant_id,starts_at,ends_at)`.

### billing-service · taska.invoices

Scope `TENANT`; retention `FINANCIAL_RECORD`. All financial snapshots immutable after issue; state is projection. Seller/tax/legal numbering content remains O08, explicit schemas in contracts; fake issuer is test-only. Receipt timing determines timeliness. method enum uses canonical WIRE/PSP. PARTIALLY_PAID reflects eligible unmatched coverage candidates in invoice_payment_matches, not final allocations.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `number` `text` NOT NULL
- `operation_id` `uuid` NOT NULL
- `purpose` `text` NOT NULL
- `plan_version_id` `uuid` NOT NULL
- `method` `text` NOT NULL
- `state` `text` NOT NULL
- `currency` `char(3)` NOT NULL
- `currency_exponent` `smallint` NOT NULL
- `subtotal_minor` `bigint` NOT NULL
- `tax_minor` `bigint` NOT NULL
- `total_minor` `bigint` NOT NULL
- `issued_at` `timestamptz` NOT NULL
- `payable_until` `timestamptz` NOT NULL
- `creation_deadline_snapshot` `timestamptz` NULL
- `terms_snapshot` `jsonb` NOT NULL
- `seller_snapshot` `jsonb` NOT NULL
- `buyer_snapshot` `jsonb` NOT NULL
- `pricing_snapshot` `jsonb` NOT NULL
- `replaces_invoice_id` `uuid` NULL
- `paid_at` `timestamptz` NULL
- `version` `bigint` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(number)`; `(tenant_id, operation_id)`.

Локальные FK:

- `(plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.
- `(tenant_id, replaces_invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `purpose IN ('FIRST_PAYMENT','RENEWAL','PLAN_CHANGE','RESUME')`.
- `method IN ('PSP','WIRE')`.
- `state IN ('DRAFT','OPEN','PARTIALLY_PAID','PAID','EXPIRED','VOID')`.
- `subtotal_minor>=0 AND tax_minor>=0 AND total_minor=subtotal_minor+tax_minor`.
- `payable_until>issued_at`.

Дополнительные indexes:

- `(tenant_id,issued_at DESC,id)`.
- `(tenant_id,payable_until,id) WHERE state IN ('OPEN','PARTIALLY_PAID')`.

### billing-service · taska.invoice_lines

Scope `TENANT`; retention `FINANCIAL_RECORD`. All lines same invoice currency/exponent; no float amounts.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `invoice_id` `uuid` NOT NULL
- `line_no` `integer` NOT NULL
- `description` `text` NOT NULL
- `quantity` `integer` NOT NULL
- `net_minor` `bigint` NOT NULL
- `tax_minor` `bigint` NOT NULL
- `tax_rate` `numeric(9,6)` NOT NULL
- `total_minor` `bigint` NOT NULL

PK `(tenant_id, invoice_id, line_no)`.

Локальные FK:

- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `line_no>0 AND quantity>0 AND net_minor>=0 AND tax_minor>=0 AND total_minor=net_minor+tax_minor`.

### billing-service · taska.payment_facts

Scope `TENANT`; retention `FINANCIAL_RECORD`. Append-only verified normalized money movement, not webhook receipt. Bank occurred_at is statement receipt; verified_at is admin confirmation. Provider evidence held privately; no raw payload in Kafka. External partial facts always recorded. occurredAt is an exact financial occurrence only for EXACT. DAY preserves the evidence-derived UTC half-open interval [occurred_not_before,occurred_before), never invented midnight. Statement calendar/timezone evidence determines bounds; ambiguous boundary creates WAITING_EVIDENCE. payer_ref is a verified opaque reference to the original payer, no arbitrary replacement beneficiary. observed_at is ingress observation, verified_at maps to DTO confirmedAt; neither substitutes financial occurredAt for timeliness. PSP exact occurrence comes from provider status; unresolved unknown-payer WIRE stays in unmatched_bank_receipts until verified.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `provider` `text` NOT NULL
- `provider_account_id` `text` NOT NULL
- `external_fact_id` `text` NOT NULL
- `fact_kind` `text` NOT NULL
- `parent_fact_id` `uuid` NULL
- `amount_minor` `bigint` NOT NULL
- `currency` `char(3)` NOT NULL
- `currency_exponent` `smallint` NOT NULL
- `occurred_at` `timestamptz` NULL
- `observed_at` `timestamptz` NOT NULL
- `verified_at` `timestamptz` NOT NULL
- `verified_by` `uuid` NULL
- `evidence_ref` `text` NOT NULL
- `evidence_digest` `bytea` NOT NULL
- `schema_version` `integer` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `occurred_precision` `text` NOT NULL
- `occurred_not_before` `timestamptz` NULL
- `occurred_before` `timestamptz` NULL
- `payer_ref` `text` NULL
- `source_method` `text` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(provider, provider_account_id, external_fact_id)`.

Локальные FK:

- `(tenant_id, parent_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `fact_kind IN ('PAYMENT','REFUND','CHARGEBACK','CHARGEBACK_REVERSAL')`.
- `amount_minor>0 AND currency_exponent BETWEEN 0 AND 3`.
- `occurred_precision IN ('EXACT','DAY')`.
- `(occurred_precision='EXACT' AND occurred_at IS NOT NULL AND occurred_not_before IS NULL AND occurred_before IS NULL) OR (occurred_precision='DAY' AND occurred_at IS NULL AND occurred_not_before IS NOT NULL AND occurred_before IS NOT NULL AND occurred_before>occurred_not_before)`.
- `source_method IN ('PSP','WIRE')`.
- `source_method<>'WIRE' OR payer_ref IS NOT NULL`.

Дополнительные indexes:

- `(tenant_id,occurred_at,id)`.
- `(tenant_id,parent_fact_id)`.

### billing-service · taska.payment_allocations

Scope `TENANT`; retention `FINANCIAL_RECORD`. Locked invoice + fact enforce SUM allocation <= verified positive amount and invoice total. Refunds do not edit immutable original allocation. Same-currency only; no silent FX on allocation. Insert allocations only when effective matched sum equals invoice total and every source is eligible. One allocation per match with identical invoice/source/amount; enforce via ordered aggregate locks plus owner DB guard trigger. Allocation + PAID invoice + period + outbox commit atomically. matched amount is not added again to allocated amount in conservation equations. eligibility_evidence is immutable VerifiedPaymentEligibility snapshot: original factId, optional selected refinement evidenceId, canonical occurred precision/time or range, verifiedBy/confirmedAt, applicable invoice/deadline and decisionCode; no raw bank documents.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `payment_fact_id` `uuid` NOT NULL
- `invoice_id` `uuid` NOT NULL
- `amount_minor` `bigint` NOT NULL
- `operation_id` `uuid` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `match_id` `uuid` NOT NULL
- `eligibility_evidence` `jsonb` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, operation_id)`; `(tenant_id, match_id)`.

Локальные FK:

- `(tenant_id, payment_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, match_id) → invoice_payment_matches(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `amount_minor>0`.
- `jsonb_typeof(eligibility_evidence)='object'`.

Дополнительные indexes:

- `(tenant_id,payment_fact_id)`.
- `(tenant_id,invoice_id)`.

### billing-service · taska.refunds

Scope `TENANT`; retention `FINANCIAL_RECORD`. Subscription amount must equal full original covered amount, no arbitrary partial product refund. Aggregate lock prevents initiating refund beyond currently available principal; externally confirmed over-return facts are still recorded, never rejected or silently clipped, and enter manual reconciliation. Confirmation fact alone triggers financial effect. Owner decisions of 2026-11-03 fix final partial-chargeback outcome and WIRE return SLA; missing/inconsistent evidence alone still requires reconciliation. kind exactly matches REST/proto SUBSCRIPTION_FULL/UNALLOCATED_RETURN; detailed accepted case is reason_code, not a second kind vocabulary. SUBSCRIPTION_FULL equals full original covered amount; a confirmed final partial chargeback revokes only linked coverage and creates no further refund intent; other ambiguous external evidence requires reconciliation and cannot create a partial product refund. UNALLOCATED_RETURN includes only principal never covered by a final payment_allocation. WIRE evidence_completed_at is the first accepted complete verified dossier; freeze WIRE_RETURN_ES_V1, business_calendar_id/version/digest, typed sla_snapshot, one decision_due_at=send_due_at and fee_bearer=TASKA atomically. Count five eligible Europe/Madrid dates after the evidence local date at the same local wall time; Mon-Fri minus versioned Spain national and seller regional/local bank holidays. DST gap uses NEXT_VALID_INSTANT; overlap LATER_OFFSET. Retry, assignee change, later calendar publication or a new evidence request never restarts the deadline. No automatic approval/submission/confirmation at SLA expiry. decided_at/submitted_at record actual events even when late; submitted_at means verified outgoing instruction acceptance, may remain NULL for UNKNOWN. outbound_fee_minor/currency is Taska expense separately evidenced, never deducted from refund amount_minor principal. Actual live calendar is provisioned with seller/Stripe merchant profile; synthetic calendar is sandbox-only. WIRE fee_bearer=TASKA is mandatory from initial request, before complete evidence; it never waits for SLA admission. PSP has no WIRE SLA bundle.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `source_payment_fact_id` `uuid` NOT NULL
- `invoice_id` `uuid` NULL
- `kind` `text` NOT NULL
- `requested_by` `uuid` NOT NULL
- `amount_minor` `bigint` NOT NULL
- `state` `text` NOT NULL
- `operation_id` `uuid` NOT NULL
- `provider_request_id` `text` NULL
- `confirmed_fact_id` `uuid` NULL
- `reason_code` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL
- `source_method` `text` NOT NULL
- `evidence_completed_at` `timestamptz` NULL
- `business_calendar_id` `text` NULL
- `calendar_version` `text` NULL
- `sla_policy_version` `text` NULL
- `sla_snapshot` `jsonb` NULL
- `decision_due_at` `timestamptz` NULL
- `send_due_at` `timestamptz` NULL
- `decided_at` `timestamptz` NULL
- `submitted_at` `timestamptz` NULL
- `fee_bearer` `text` NULL
- `outbound_fee_minor` `bigint` NULL
- `outbound_fee_currency` `char(3)` NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, operation_id)`.

Локальные FK:

- `(tenant_id, source_payment_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, confirmed_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('REQUESTED','APPROVED','SUBMITTING','UNKNOWN','CONFIRMED','FAILED','REJECTED')`.
- `amount_minor>0`.
- `kind IN ('SUBSCRIPTION_FULL','UNALLOCATED_RETURN')`.
- `reason_code IN ('BILLING_ERROR','SERVICE_FAILURE','MANDATORY','EXCESS','LATE_FIRST_PAYMENT','CLOSED_CREATION','INCOMPLETE_BANK_PAYMENT','DUPLICATE_PAYMENT')`.
- `(kind='SUBSCRIPTION_FULL' AND reason_code IN ('BILLING_ERROR','SERVICE_FAILURE','MANDATORY')) OR (kind='UNALLOCATED_RETURN' AND reason_code IN ('EXCESS','LATE_FIRST_PAYMENT','CLOSED_CREATION','INCOMPLETE_BANK_PAYMENT','DUPLICATE_PAYMENT'))`.
- `source_method IN ('PSP','WIRE')`.
- `source_method<>'WIRE' OR state NOT IN ('APPROVED','SUBMITTING','UNKNOWN','CONFIRMED') OR evidence_completed_at IS NOT NULL`.
- `sla_policy_version IS NULL OR sla_policy_version='WIRE_RETURN_ES_V1'`.
- `decision_due_at IS NULL OR (decision_due_at=send_due_at AND decision_due_at>evidence_completed_at)`.
- `(source_method='WIRE' AND fee_bearer IS NOT NULL AND fee_bearer='TASKA') OR (source_method='PSP' AND fee_bearer IS NULL)`.
- `(evidence_completed_at IS NULL AND business_calendar_id IS NULL AND calendar_version IS NULL AND sla_policy_version IS NULL AND sla_snapshot IS NULL AND decision_due_at IS NULL AND send_due_at IS NULL) OR (source_method='WIRE' AND evidence_completed_at IS NOT NULL AND business_calendar_id IS NOT NULL AND calendar_version IS NOT NULL AND sla_policy_version IS NOT NULL AND sla_snapshot IS NOT NULL AND decision_due_at IS NOT NULL AND send_due_at IS NOT NULL)`.
- `(outbound_fee_minor IS NULL AND outbound_fee_currency IS NULL) OR (outbound_fee_minor IS NOT NULL AND outbound_fee_minor>=0 AND outbound_fee_currency IS NOT NULL)`.
- `sla_snapshot IS NULL OR (jsonb_typeof(sla_snapshot)='object' AND sla_snapshot ?& ARRAY['businessDays','feeBearer','countingRule','timezone','excludeEvidenceDate','dstGapRule','dstOverlapRule','calendarDigest'] AND sla_snapshot - ARRAY['businessDays','feeBearer','countingRule','timezone','excludeEvidenceDate','dstGapRule','dstOverlapRule','calendarDigest'] = '{}'::jsonb AND sla_snapshot @> '{"businessDays":5,"feeBearer":"TASKA","countingRule":"T_PLUS_5_BUSINESS_DATES_SAME_LOCAL_TIME","timezone":"Europe/Madrid","excludeEvidenceDate":true,"dstGapRule":"NEXT_VALID_INSTANT","dstOverlapRule":"LATER_OFFSET"}'::jsonb AND jsonb_typeof(sla_snapshot->'calendarDigest')='string' AND sla_snapshot->>'calendarDigest' ~ '^[0-9a-f]{64}$')`.

Дополнительные indexes:

- `(tenant_id,source_payment_fact_id,state)`.
- `(tenant_id,decision_due_at,id) WHERE source_method='WIRE' AND evidence_completed_at IS NOT NULL AND decided_at IS NULL`.
- `(tenant_id,send_due_at,id) WHERE source_method='WIRE' AND evidence_completed_at IS NOT NULL AND submitted_at IS NULL AND state NOT IN ('REJECTED','CONFIRMED')`.

Frozen SLA guard: `source_method` и `fee_bearer` неизменяемы с создания; после первого полного `evidence_completed_at` весь SLA bundle неизменяем, включая попытку очистить его. SQLSTATE23514. Смена assignment/retry/calendar не сдвигает clock.

### billing-service · taska.disputes

Scope `TENANT`; retention `FINANCIAL_RECORD`. Confirmed final provider resolution only. WON and WITHDRAWN_CONFIRMED preserve the original period remainder without creating a new period; no new money fact is required when funds never left. LOST_FULL_REFUND and LOST_PARTIAL_COVERAGE_REVOKED persist state LOST and require verified linked monetary loss fact. Final partial chargeback revokes only coverage backed by that source payment; independent current coverage survives. It creates no additional outgoing refund/return intent. Regaining paid coverage requires a new full payment and full month (PSP success / WIRE admin confirmation); no partial top-up obligation. Old autopay mandate remains revoked; new owner consent required. NEEDS_REVIEW is transient for absent/inconsistent evidence, never the final policy for an already confirmed partial loss. closed_at maps to resolvedAt. Resolution evidence and final_outcome are immutable once accepted; later duplicate/out-of-order provider events cannot replace a newer terminal decision. Owner transaction verifies confirmed money kind/currency/amount and source linkage; simple FK alone does not prove a partial chargeback.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `payment_fact_id` `uuid` NOT NULL
- `provider` `text` NOT NULL
- `external_dispute_id` `text` NOT NULL
- `state` `text` NOT NULL
- `amount_minor` `bigint` NOT NULL
- `confirmed_money_fact_id` `uuid` NULL
- `version` `bigint` NOT NULL
- `opened_at` `timestamptz` NOT NULL
- `closed_at` `timestamptz` NULL
- `final_outcome` `text` NULL
- `resolution_evidence_ref` `text` NULL
- `resolution_evidence_digest` `bytea` NULL
- `resolution_verified_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(provider, external_dispute_id)`.

Локальные FK:

- `(tenant_id, payment_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, confirmed_money_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('OPEN','WON','LOST','WITHDRAWN','NEEDS_REVIEW')`.
- `amount_minor>0 AND version>0`.
- `final_outcome IS NULL OR final_outcome IN ('WON','WITHDRAWN_CONFIRMED','LOST_FULL_REFUND','LOST_PARTIAL_COVERAGE_REVOKED')`.
- `final_outcome IS NULL OR (resolution_evidence_ref IS NOT NULL AND resolution_evidence_digest IS NOT NULL AND resolution_verified_at IS NOT NULL)`.
- `final_outcome NOT IN ('LOST_FULL_REFUND','LOST_PARTIAL_COVERAGE_REVOKED') OR confirmed_money_fact_id IS NOT NULL`.
- `(state IN ('OPEN','NEEDS_REVIEW') AND final_outcome IS NULL AND closed_at IS NULL) OR (final_outcome IS NOT NULL AND closed_at IS NOT NULL AND ((state='WON' AND final_outcome='WON') OR (state='WITHDRAWN' AND final_outcome='WITHDRAWN_CONFIRMED') OR (state='LOST' AND final_outcome IN ('LOST_FULL_REFUND','LOST_PARTIAL_COVERAGE_REVOKED'))))`.

Дополнительные indexes:

- `(tenant_id,payment_fact_id,state)`.

### billing-service · taska.payment_methods

Scope `TENANT`; retention `FINANCIAL_RECORD`. Opaque provider references, no PAN/CVV/bank secret. Owner transfer revokes linkage for new charges.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `owner_id` `uuid` NOT NULL
- `provider` `text` NOT NULL
- `provider_customer_ref` `text` NOT NULL
- `provider_method_ref` `text` NOT NULL
- `status` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `revoked_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`.

CHECK:

- `status IN ('ACTIVE','REVOKED')`.

### billing-service · taska.autopay_mandates

Scope `TENANT`; retention `FINANCIAL_RECORD`. No SUSPENDED->ACTIVE reactivation: transfer/dispute/refund revocation needs new mandate/consent. Pause flags stop new attempts; accepted attempt reconciles. Immutable consent snapshot conforms to transport ConsentSnapshot: exact consent wording/template revision and accepted configuration, terms version, channel, owner version and consented_at; live owner_id AND owner_version checked before each new dispatch. A transfer increments owner_version in the same tenant transaction.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `owner_id` `uuid` NOT NULL
- `payment_method_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `consent_version` `bigint` NOT NULL
- `consented_at` `timestamptz` NOT NULL
- `revoked_at` `timestamptz` NULL
- `revocation_reason` `text` NULL
- `owner_version` `bigint` NOT NULL
- `consent_terms_version` `text` NOT NULL
- `consent_channel` `text` NOT NULL
- `consent_snapshot` `jsonb` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`.

Локальные FK:

- `(tenant_id, payment_method_id) → payment_methods(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('ACTIVE','REVOKED')`.
- `owner_version>0`.
- `consent_channel IN ('WEB','PAT','OAUTH','MCP')`.
- `jsonb_typeof(consent_snapshot)='object'`.

Дополнительные indexes:

- `UNIQUE (tenant_id) WHERE state='ACTIVE'`.

### billing-service · taska.payment_attempts

Scope `TENANT`; retention `FINANCIAL_RECORD`. UNKNOWN is reconciled by provider idempotency key before another attempt. Redirect is never confirmation.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `invoice_id` `uuid` NOT NULL
- `mandate_id` `uuid` NULL
- `provider` `text` NOT NULL
- `provider_idempotency_key` `text` NOT NULL
- `provider_payment_id` `text` NULL
- `state` `text` NOT NULL
- `accepted_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(provider, provider_idempotency_key)`.

Локальные FK:

- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, mandate_id) → autopay_mandates(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('ACCEPTED','SUBMITTED','PENDING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')`.

Дополнительные indexes:

- `(tenant_id,invoice_id,state)`.

### billing-service · taska.entitlement_snapshots

Scope `TENANT`; retention `FINANCIAL_RECORD`. Immutable output version; not actor capabilities. Limit cache keyed tenant+version; validity reevaluated by clock even without event.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `version` `bigint` NOT NULL
- `plan_version_id` `uuid` NOT NULL
- `period_id` `uuid` NULL
- `valid_from` `timestamptz` NOT NULL
- `valid_until` `timestamptz` NULL
- `max_projects` `bigint` NOT NULL
- `max_members` `bigint` NOT NULL
- `max_tasks_per_project` `bigint` NULL
- `max_storage_bytes` `bigint` NOT NULL
- `features` `jsonb` NOT NULL
- `coverage_state` `text` NOT NULL
- `source_operation_id` `uuid` NOT NULL
- `created_at` `timestamptz` NOT NULL

PK `(tenant_id, version)`.

UNIQUE: `(tenant_id, source_operation_id)`.

Локальные FK:

- `(plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.
- `(tenant_id, period_id) → subscription_periods(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `version>0`.
- `coverage_state IN ('FREE','PAID','UNPAID','DISPUTED','REVOKED')`.

### billing-service · taska.change_notices

Scope `TENANT`; retention `FINANCIAL_RECORD`. Countdown starts only after durable inbox-created receipt, not queued event/email attempt/read. Email retries independent. FREE effective time direct; paid first anchor boundary after warning.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `new_plan_version_id` `uuid` NOT NULL
- `previous_plan_version_id` `uuid` NOT NULL
- `mandatory_inbox_id` `uuid` NULL
- `inbox_created_at` `timestamptz` NULL
- `effective_at` `timestamptz` NULL
- `warning_seconds` `bigint` NOT NULL
- `state` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, new_plan_version_id)`.

Локальные FK:

- `(new_plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.
- `(previous_plan_version_id) → catalog_plan_versions(id) ON DELETE RESTRICT`.

CHECK:

- `warning_seconds>=0`.
- `state IN ('PENDING_INBOX','NOTICE_CREATED','APPLIED','SUPERSEDED')`.

Дополнительные indexes:

- `(tenant_id,effective_at,id)`.

### billing-service · taska.settlement_cases

Scope `TENANT`; retention `FINANCIAL_RECORD`. Absent/inconsistent evidence remains WAITING_EVIDENCE; normalized facts are never discarded. The accepted 2026-11-03 final partial chargeback outcome is LOST_PARTIAL_COVERAGE_REVOKED: apply linked coverage revocation and resolve the case without initiating another outgoing refund. Bank return service deadline follows frozen WIRE_RETURN_ES_V1; C03/C05 are no longer unresolved owner-policy gates. D236/D237 allocation and excess tracked separately. evidence_log is append-only FinancialEvidenceRefinement[]; each entry has evidenceId/sourceFactId/evidenceRef/digest/occurredPrecision/occurredAt or bounds/payerRef/verifiedBy/verifiedAt. Existing prefix cannot be edited or removed (DB guard trigger). It refines knowledge about one monetary fact, never creates another movement. Command dedupes evidenceId and stores immutable canonical digest; precise supplemental evidence does not overwrite original DAY fact. Confirm uses selected verified evidenceId and freezes its eligibility snapshot in final allocation.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `payment_fact_id` `uuid` NOT NULL
- `invoice_id` `uuid` NULL
- `kind` `text` NOT NULL
- `state` `text` NOT NULL
- `policy_version` `text` NULL
- `resolution_operation_id` `uuid` NULL
- `created_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL
- `evidence_log` `jsonb` NOT NULL DEFAULT `'[]'::jsonb`

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, payment_fact_id, kind)`.

Локальные FK:

- `(tenant_id, payment_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `kind IN ('UNMATCHED','LATE','PARTIAL','OVERPAYMENT','EXTERNAL_PARTIAL_REFUND','PARTIAL_CHARGEBACK','DUPLICATE_PAYMENT')`.
- `state IN ('OPEN','WAITING_EVIDENCE','RETURN_REQUIRED','RETURN_IN_PROGRESS','RESOLVED')`.
- `jsonb_typeof(evidence_log)='array'`.

Дополнительные indexes:

- `(tenant_id,state,created_at,id)`.

### billing-service · taska.unmatched_bank_receipts

Scope `GLOBAL_FINANCIAL`; retention `FINANCIAL_RECORD`. Unmatched receipt cannot invent tenant_id. Admin-only intake; assignment is audited and creates one verified tenant payment fact, preserving global dedupe. occurredAt is an exact financial occurrence only for EXACT. DAY preserves the evidence-derived UTC half-open interval [occurred_not_before,occurred_before), never invented midnight. Statement calendar/timezone evidence determines bounds; ambiguous boundary creates WAITING_EVIDENCE. payer_ref is a verified opaque reference to the original payer, no arbitrary replacement beneficiary.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `provider_account_id` `text` NOT NULL
- `external_receipt_id` `text` NOT NULL
- `amount_minor` `bigint` NOT NULL
- `currency` `char(3)` NOT NULL
- `occurred_at` `timestamptz` NULL
- `evidence_ref` `text` NOT NULL
- `evidence_digest` `bytea` NOT NULL
- `assigned_tenant_id` `uuid` NULL
- `payment_fact_id` `uuid` NULL
- `state` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `occurred_precision` `text` NOT NULL
- `occurred_not_before` `timestamptz` NULL
- `occurred_before` `timestamptz` NULL
- `payer_ref` `text` NULL
- `observed_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(provider_account_id, external_receipt_id)`.

CHECK:

- `amount_minor>0 AND currency='EUR'`.
- `state IN ('UNMATCHED','ASSIGNED','RETURN_PENDING','RETURNED','POLICY_HOLD')`.
- `occurred_precision IN ('EXACT','DAY')`.
- `(occurred_precision='EXACT' AND occurred_at IS NOT NULL AND occurred_not_before IS NULL AND occurred_before IS NULL) OR (occurred_precision='DAY' AND occurred_at IS NULL AND occurred_not_before IS NOT NULL AND occurred_before IS NOT NULL AND occurred_before>occurred_not_before)`.
- `state NOT IN ('ASSIGNED','RETURN_PENDING','RETURNED') OR payer_ref IS NOT NULL`.

Дополнительные indexes:

- `(state,occurred_at,id)`.

### billing-service · taska.provider_inbox

Scope `GLOBAL_FINANCIAL`; retention `FINANCIAL_RECORD`. Authenticated transport inbox is not payment fact. Private encrypted raw payload object, bounded access, retention O09; outbox carries normalized safe references.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `provider` `text` NOT NULL
- `provider_account_id` `text` NOT NULL
- `event_id` `text` NOT NULL
- `received_at` `timestamptz` NOT NULL
- `payload_ref` `text` NOT NULL
- `payload_digest` `bytea` NOT NULL
- `signature_verified` `boolean` NOT NULL
- `state` `text` NOT NULL
- `assigned_tenant_id` `uuid` NULL
- `processed_at` `timestamptz` NULL

PK `(provider, provider_account_id, event_id)`.

CHECK:

- `state IN ('RECEIVED','VERIFIED','APPLIED','IGNORED','QUARANTINED')`.

Дополнительные indexes:

- `(state,received_at,event_id)`.

### billing-service · taska.fx_quotes

Scope `GLOBAL`; retention `CATALOG_HISTORY`. EUR source price; rate and final rounding snapshotted in invoice. Provider/source/currency launch list O08. HALF_UP once on final invoice minor amount. No floating point.

ES_V1 mapping: `NON_PERSONAL_CONFIGURATION` / `active/referenced configuration`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `base_currency` `char(3)` NOT NULL
- `quote_currency` `char(3)` NOT NULL
- `rate` `numeric(20,10)` NOT NULL
- `source` `text` NOT NULL
- `quoted_at` `timestamptz` NOT NULL
- `valid_until` `timestamptz` NOT NULL
- `version` `text` NOT NULL

PK `(id)`.

CHECK:

- `rate>0 AND valid_until>quoted_at`.

Дополнительные indexes:

- `(base_currency,quote_currency,quoted_at DESC)`.

### tenant-service · taska.idempotency_records

Scope `TENANT`; retention `IDEMPOTENCY_RESPONSE_TTL`. 24h response-cache TTL technical default; domain operation/payment/receipt dedupe identity survives cache eviction. Hash mismatch is conflict; replay rechecks current visibility.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `actor_id` `uuid` NOT NULL
- `operation_code` `text` NOT NULL
- `key` `varchar(128)` NOT NULL
- `request_hash` `bytea` NOT NULL
- `operation_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `response` `jsonb` NULL
- `created_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NOT NULL

PK `(tenant_id, actor_id, operation_code, key)`.

CHECK:

- `state IN ('IN_PROGRESS','SUCCEEDED','FAILED')`.
- `expires_at>created_at`.

### tenant-service · taska.outbox_events

Scope `TENANT`; retention `OUTBOX_REPLAY_SAFETY`. Commit with business mutation; tenant queues selected fairly. Platform config/catalog has separate global_outbox_events to avoid nullable tenant escape.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `uuid` NOT NULL
- `aggregate_version` `bigint` NOT NULL
- `event_type` `text` NOT NULL
- `schema_version` `integer` NOT NULL
- `payload` `jsonb` NOT NULL
- `operation_id` `uuid` NOT NULL
- `status` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `published_at` `timestamptz` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `processing_started_at` `timestamptz` NULL
- `last_error_code` `text` NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type)`.

CHECK:

- `status IN ('NEW','PROCESSING','PUBLISHED','FAILED')`.
- `attempts>=0 AND schema_version>0`.

Дополнительные indexes:

- `(tenant_id,status,created_at,id) WHERE status IN ('NEW','PROCESSING')`.

### tenant-service · taska.global_outbox_events

Scope `GLOBAL`; retention `OUTBOX_REPLAY_SAFETY`. Only catalog/config/identity-free aggregate data; no tenant business payload.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `text` NOT NULL
- `aggregate_version` `bigint` NOT NULL
- `event_type` `text` NOT NULL
- `schema_version` `integer` NOT NULL
- `payload` `jsonb` NOT NULL
- `status` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `published_at` `timestamptz` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`

PK `(id)`.

UNIQUE: `(aggregate_type, aggregate_id, aggregate_version, event_type)`.

CHECK:

- `status IN ('NEW','PROCESSING','PUBLISHED','FAILED')`.

Дополнительные indexes:

- `(status,created_at,id) WHERE status IN ('NEW','PROCESSING')`.

### tenant-service · taska.processed_events

Scope `TENANT`; retention `OUTBOX_REPLAY_SAFETY`. Insert dedupe row in same local transaction as effect; event dedupe does not replace aggregate version checks.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `consumer` `text` NOT NULL
- `event_id` `uuid` NOT NULL
- `processed_at` `timestamptz` NOT NULL
- `result_version` `bigint` NULL

PK `(tenant_id, consumer, event_id)`.

### billing-service · taska.idempotency_records

Scope `TENANT`; retention `IDEMPOTENCY_RESPONSE_TTL`. 24h response-cache TTL technical default; domain operation/payment/receipt dedupe identity survives cache eviction. Hash mismatch is conflict; replay rechecks current visibility.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `actor_id` `uuid` NOT NULL
- `operation_code` `text` NOT NULL
- `key` `varchar(128)` NOT NULL
- `request_hash` `bytea` NOT NULL
- `operation_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `response` `jsonb` NULL
- `created_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NOT NULL

PK `(tenant_id, actor_id, operation_code, key)`.

CHECK:

- `state IN ('IN_PROGRESS','SUCCEEDED','FAILED')`.
- `expires_at>created_at`.

### billing-service · taska.outbox_events

Scope `TENANT`; retention `OUTBOX_REPLAY_SAFETY`. Commit with business mutation; tenant queues selected fairly. Platform config/catalog has separate global_outbox_events to avoid nullable tenant escape.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `uuid` NOT NULL
- `aggregate_version` `bigint` NOT NULL
- `event_type` `text` NOT NULL
- `schema_version` `integer` NOT NULL
- `payload` `jsonb` NOT NULL
- `operation_id` `uuid` NOT NULL
- `status` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `published_at` `timestamptz` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `processing_started_at` `timestamptz` NULL
- `last_error_code` `text` NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type)`.

CHECK:

- `status IN ('NEW','PROCESSING','PUBLISHED','FAILED')`.
- `attempts>=0 AND schema_version>0`.

Дополнительные indexes:

- `(tenant_id,status,created_at,id) WHERE status IN ('NEW','PROCESSING')`.

### billing-service · taska.global_outbox_events

Scope `GLOBAL`; retention `OUTBOX_REPLAY_SAFETY`. Only catalog/config/identity-free aggregate data; no tenant business payload.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `text` NOT NULL
- `aggregate_version` `bigint` NOT NULL
- `event_type` `text` NOT NULL
- `schema_version` `integer` NOT NULL
- `payload` `jsonb` NOT NULL
- `status` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `published_at` `timestamptz` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`

PK `(id)`.

UNIQUE: `(aggregate_type, aggregate_id, aggregate_version, event_type)`.

CHECK:

- `status IN ('NEW','PROCESSING','PUBLISHED','FAILED')`.

Дополнительные indexes:

- `(status,created_at,id) WHERE status IN ('NEW','PROCESSING')`.

### billing-service · taska.processed_events

Scope `TENANT`; retention `OUTBOX_REPLAY_SAFETY`. Insert dedupe row in same local transaction as effect; event dedupe does not replace aggregate version checks.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `consumer` `text` NOT NULL
- `event_id` `uuid` NOT NULL
- `processed_at` `timestamptz` NOT NULL
- `result_version` `bigint` NULL

PK `(tenant_id, consumer, event_id)`.

### billing-service · taska.billing_operations

Scope `TENANT`; retention `FINANCIAL_RECORD`. Permanent settlement/renewal/refund identity; deterministic keys for scheduler period+purpose. No actor nullable for manual action; nullable only verified provider/scheduler subject.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `kind` `text` NOT NULL
- `actor_id` `uuid` NULL
- `channel` `text` NOT NULL
- `grant_id` `uuid` NULL
- `request_key` `varchar(128)` NOT NULL
- `request_hash` `bytea` NOT NULL
- `state` `text` NOT NULL
- `fence` `bigint` NOT NULL
- `result` `jsonb` NULL
- `created_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, kind, request_key)`.

CHECK:

- `state IN ('NEW','PROCESSING','UNKNOWN','SUCCEEDED','FAILED')`.
- `fence>0`.

Дополнительные indexes:

- `(tenant_id,state,updated_at,id) WHERE state IN ('NEW','PROCESSING','UNKNOWN')`.

### auth-service · taska.web_sessions

Scope `GLOBAL_ACCOUNT`; retention `SESSION_SECURITY`. Admin web session only after email MFA; session claim does not replace current global-role check.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `assurance` `text` NOT NULL
- `auth_version` `bigint` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NOT NULL
- `revoked_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(id, user_id)`.

Локальные FK:

- `(user_id) → users(id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('ACTIVE','REVOKED','EXPIRED')`.
- `assurance IN ('PASSWORD','SOCIAL','ADMIN_EMAIL_MFA')`.
- `expires_at>created_at`.

Дополнительные indexes:

- `(user_id,state,id)`.

### auth-service · taska.tenant_sessions

Scope `GLOBAL_ACCOUNT`; retention `SESSION_SECURITY`. Opaque independent context per tab; no single selectedTenant field on global user/session. Tenant membership logical RPC reference.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `web_session_id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `membership_version` `bigint` NOT NULL
- `version` `bigint` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NOT NULL
- `revoked_at` `timestamptz` NULL

PK `(id)`.

Локальные FK:

- `(web_session_id, user_id) → web_sessions(id, user_id) ON DELETE RESTRICT`.
- `(user_id) → users(id) ON DELETE RESTRICT`.

CHECK:

- `version>0 AND expires_at>created_at`.

Дополнительные indexes:

- `(user_id,tenant_id,id)`.
- `(tenant_id,user_id)`.

### auth-service · taska.integration_grants

Scope `GLOBAL_ACCOUNT`; retention `SESSION_SECURITY`. ONE_TENANT only for ordinary integrations; ADMIN PLATFORM required for global account actions, live role check mandatory. permissions from allowlist, widening fresh explicit consent.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `kind` `text` NOT NULL
- `scope_type` `text` NOT NULL
- `tenant_id` `uuid` NULL
- `access_level` `text` NOT NULL
- `permissions` `text[]` NOT NULL
- `version` `bigint` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NULL
- `revoked_at` `timestamptz` NULL

PK `(id)`.

Локальные FK:

- `(user_id) → users(id) ON DELETE RESTRICT`.

CHECK:

- `kind IN ('USER','ADMIN')`.
- `scope_type IN ('ONE_TENANT','TENANT_SET','PLATFORM')`.
- `access_level IN ('READ','MODIFY')`.
- `(scope_type='ONE_TENANT')=(tenant_id IS NOT NULL)`.
- `kind='ADMIN' OR (scope_type='ONE_TENANT' AND NOT ('ACCOUNT_ADMIN'=ANY(permissions)))`.
- `kind<>'ADMIN' OR scope_type IN ('TENANT_SET','PLATFORM')`.
- `version>0`.

Дополнительные indexes:

- `(user_id,revoked_at,id)`.

### auth-service · taska.integration_grant_tenants

Scope `GLOBAL_ACCOUNT`; retention `SESSION_SECURITY`. Only TENANT_SET grant; service transaction validates nonempty set and type, never ordinary USER. Target tenants logical cross-service refs.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `grant_id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL

PK `(grant_id, tenant_id)`.

Локальные FK:

- `(grant_id) → integration_grants(id) ON DELETE CASCADE`.

Дополнительные indexes:

- `(tenant_id,grant_id)`.

### auth-service · taska.confirmation_challenges

Scope `GLOBAL_ACCOUNT`; retention `SHORT_LIVED_SECRET`. 10min absolute TTL, 5 attempts, resend every 60s maximum 3 without extending expiry; eight-digit random code stored HMAC with per-challenge salt outside plaintext. Deletion purpose/target/version distinct; transfer/bank-confirm/refund do not use OTP.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `purpose` `text` NOT NULL
- `target_id` `uuid` NULL
- `target_version` `bigint` NOT NULL
- `attempt_id` `uuid` NOT NULL
- `code_hash` `bytea` NOT NULL
- `channel` `text` NOT NULL
- `attempts` `smallint` NOT NULL DEFAULT `0`
- `max_attempts` `smallint` NOT NULL
- `resend_count` `smallint` NOT NULL DEFAULT `0`
- `last_sent_at` `timestamptz` NOT NULL
- `issued_at` `timestamptz` NOT NULL
- `expires_at` `timestamptz` NOT NULL
- `consumed_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(attempt_id)`.

Локальные FK:

- `(user_id) → users(id) ON DELETE RESTRICT`.

CHECK:

- `purpose IN ('ADMIN_LOGIN','DELETE_ACCOUNT','DELETE_TENANT','VERIFY_RECOVERY_EMAIL')`.
- `channel IN ('EMAIL','TELEGRAM')`.
- `purpose<>'ADMIN_LOGIN' OR channel='EMAIL'`.
- `attempts>=0 AND attempts<=max_attempts AND expires_at>issued_at`.

Дополнительные indexes:

- `(user_id,purpose,expires_at)`.

### auth-service · taska.account_deletion_operations

Scope `GLOBAL_ACCOUNT`; retention `PRIVACY_CASE`. Auth/tenant deletion lock prevents concurrent owner/last-admin assignments; repeat owner/last-admin preconditions before irreversible execution. Preserve global tombstone author ID and mask snapshots.

ES_V1 mapping: `PRIVACY_CASE` / `deletion_evidence`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `challenge_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `membership_snapshot_version` `bigint` NOT NULL
- `steps` `jsonb` NOT NULL
- `retention_policy_version` `bigint` NULL
- `retention_hold_version` `bigint` NULL
- `retention_snapshot` `jsonb` NULL
- `created_at` `timestamptz` NOT NULL
- `updated_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(challenge_id)`.

Локальные FK:

- `(user_id) → users(id) ON DELETE RESTRICT`.
- `(challenge_id) → confirmation_challenges(id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('ACCEPTED','REVOKING','REMOVING_MEMBERSHIPS','ANONYMIZING','COMPLETED','RETRYABLE_FAILURE','POLICY_HOLD')`.

### auth-service · taska.avatar_object_manifest

Scope `GLOBAL_ACCOUNT`; retention `OBJECT_LIFECYCLE`. Not tenant quota; avatars/{userId}/{objectId}. Replacement marks old object for durable deletion; account FK is RESTRICT until manifest/tombstone lifecycle resolved. Upload reservation TTL 15min, PUT <=5min. Pin stagingVersionId after HEAD then server-copy that exact version to immutable final object; confirm references finalObjectVersion and checksumSha256. leaseEpoch fences retry/delete workers, never read mutable latest object after validation.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_user_avatars`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `object_key` `text` NOT NULL
- `state` `text` NOT NULL
- `generation` `bigint` NOT NULL
- `expected_bytes` `bigint` NOT NULL
- `confirmed_bytes` `bigint` NULL
- `content_type` `text` NOT NULL
- `expires_at` `timestamptz` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `delete_lease_id` `uuid` NULL
- `delete_lease_until` `timestamptz` NULL
- `staging_object_key` `text` NOT NULL
- `staging_version_id` `text` NULL
- `final_object_version` `text` NULL
- `lease_epoch` `bigint` NOT NULL DEFAULT `1`
- `checksum_sha256` `text` NULL

PK `(id)`.

UNIQUE: `(object_key)`; `(staging_object_key)`.

Локальные FK:

- `(user_id) → users(id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('RESERVED','UPLOADED','CONFIRMED','DELETE_PENDING','DELETED','EXPIRED')`.
- `expected_bytes>0 AND expected_bytes<=2000000`.
- `confirmed_bytes IS NULL OR confirmed_bytes BETWEEN 1 AND 2000000`.
- `lease_epoch>0`.
- `checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'`.
- `state<>'CONFIRMED' OR (staging_version_id IS NOT NULL AND final_object_version IS NOT NULL AND checksum_sha256 IS NOT NULL)`.

Дополнительные indexes:

- `(user_id,state,id)`.
- `(state,expires_at,id)`.

### workflow-service · taska.workflow_templates

Scope `GLOBAL`; retention `PLATFORM_CONFIGURATION`. Read-only published graph schema contains status keys/categories, transition endpoints, validator rules. Instantiate new tenant/project rows; no runtime FK to another tenant graph.

ES_V1 mapping: `NON_PERSONAL_CONFIGURATION` / `active/referenced configuration`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `code` `text` NOT NULL
- `version` `bigint` NOT NULL
- `name` `text` NOT NULL
- `graph_schema_version` `integer` NOT NULL
- `graph` `jsonb` NOT NULL
- `published_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(code, version)`.

CHECK:

- `version>0 AND graph_schema_version>0`.

### project-service · taska.resource_operation_receipts

Scope `TENANT`; retention `TENANT_WORKDATA`. Write resource+receipt+outbox atomically. Admit only matching active reservation generation; query receipt on ambiguous commit before releasing quota. Retain fence after physical business resource removal.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `resource_kind` `text` NOT NULL
- `resource_id` `uuid` NOT NULL
- `reservation_id` `uuid` NOT NULL
- `reservation_fence` `bigint` NOT NULL
- `state` `text` NOT NULL
- `amount` `bigint` NOT NULL
- `committed_at` `timestamptz` NULL

PK `(tenant_id, operation_id, resource_kind)`.

UNIQUE: `(tenant_id, resource_kind, resource_id, reservation_id)`.

CHECK:

- `state IN ('COMMITTED','CANCELLED','DELETED')`.
- `amount>0 AND reservation_fence>0`.

### issue-service · taska.resource_operation_receipts

Scope `TENANT`; retention `TENANT_WORKDATA`. Write resource+receipt+outbox atomically. Admit only matching active reservation generation; query receipt on ambiguous commit before releasing quota. Retain fence after physical business resource removal.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `resource_kind` `text` NOT NULL
- `resource_id` `uuid` NOT NULL
- `reservation_id` `uuid` NOT NULL
- `reservation_fence` `bigint` NOT NULL
- `state` `text` NOT NULL
- `amount` `bigint` NOT NULL
- `committed_at` `timestamptz` NULL

PK `(tenant_id, operation_id, resource_kind)`.

UNIQUE: `(tenant_id, resource_kind, resource_id, reservation_id)`.

CHECK:

- `state IN ('COMMITTED','CANCELLED','DELETED')`.
- `amount>0 AND reservation_fence>0`.

### issue-service · taska.attachment_object_manifest

Scope `TENANT`; retention `OBJECT_LIFECYCLE`. tenant/{tenantId}/project/{projectId}/issue/{issueId}/{objectId}; HEAD confirms actual bytes/MIME; usage released only by idempotent confirmed object deletion effect, no negative quota after failed S3 delete. Upload reservation TTL 15min, PUT <=5min. Pin stagingVersionId after HEAD then server-copy that exact version to immutable final object; confirm references finalObjectVersion and checksumSha256. leaseEpoch fences retry/delete workers, never read mutable latest object after validation.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_workfiles`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `issue_id` `uuid` NOT NULL
- `actor_id` `uuid` NOT NULL
- `object_key` `text` NOT NULL
- `reservation_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `generation` `bigint` NOT NULL
- `state` `text` NOT NULL
- `expected_bytes` `bigint` NOT NULL
- `confirmed_bytes` `bigint` NULL
- `content_type` `text` NOT NULL
- `checksum_sha256` `text` NULL
- `expires_at` `timestamptz` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `delete_lease_id` `uuid` NULL
- `delete_lease_until` `timestamptz` NULL
- `staging_object_key` `text` NOT NULL
- `staging_version_id` `text` NULL
- `final_object_version` `text` NULL
- `lease_epoch` `bigint` NOT NULL DEFAULT `1`

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(object_key)`; `(tenant_id, operation_id)`; `(staging_object_key)`.

Локальные FK:

- `(tenant_id, issue_id) → issues(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `state IN ('RESERVED','UPLOADED','CONFIRMED','DELETE_PENDING','DELETED','EXPIRED')`.
- `expected_bytes>0 AND expected_bytes<=20000000`.
- `confirmed_bytes IS NULL OR confirmed_bytes BETWEEN 1 AND 20000000`.
- `generation>0`.
- `lease_epoch>0`.
- `checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'`.
- `state<>'CONFIRMED' OR (staging_version_id IS NOT NULL AND final_object_version IS NOT NULL AND checksum_sha256 IS NOT NULL)`.

Дополнительные indexes:

- `(tenant_id,state,expires_at,id)`.
- `(tenant_id,project_id,state)`.

### notification-service · taska.account_notification_settings

Scope `GLOBAL_ACCOUNT`; retention `DELETED_PROFILE`. Global contact binding, consent/version; ordinary task opt-ins are per tenant in notification_preferences. Mandatory security channels separate.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `user_id` `uuid` NOT NULL
- `email` `varchar(320)` NOT NULL
- `email_version` `bigint` NOT NULL
- `telegram_chat_id` `bigint` NULL
- `telegram_user_id` `bigint` NULL
- `telegram_username` `text` NULL
- `telegram_name` `text` NULL
- `telegram_bot_username` `text` NULL
- `telegram_version` `bigint` NOT NULL
- `telegram_error` `text` NULL
- `updated_at` `timestamptz` NOT NULL

PK `(user_id)`.

UNIQUE: `(telegram_chat_id)`; `(telegram_user_id)`.

### notification-service · taska.account_notifications

Scope `GLOBAL_ACCOUNT`; retention `DELETED_PROFILE`. Account security only; no tenant task/project/financial payload.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `type` `text` NOT NULL
- `source_event_id` `uuid` NOT NULL
- `title` `text` NOT NULL
- `body` `text` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `read_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(user_id, source_event_id)`.

Дополнительные indexes:

- `(user_id,created_at DESC,id)`.

### notification-service · taska.account_message_deliveries

Scope `GLOBAL_ACCOUNT`; retention `SHORT_LIVED_SECRET`. Global registration/MFA/deletion code delivery. secret_ref retrieved only by authorized sender; expiry destroys secret even if retry remains.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `purpose` `text` NOT NULL
- `source_event_id` `uuid` NOT NULL
- `channel` `text` NOT NULL
- `secret_ref` `text` NULL
- `state` `text` NOT NULL
- `attempts` `integer` NOT NULL
- `expires_at` `timestamptz` NULL
- `next_attempt_at` `timestamptz` NOT NULL
- `lease_id` `uuid` NULL
- `created_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(user_id, purpose, source_event_id, channel)`.

CHECK:

- `channel IN ('EMAIL','TELEGRAM')`.
- `state IN ('PENDING','SENDING','SENT','FAILED','EXPIRED','CANCELLED')`.
- `attempts>=0`.

Дополнительные indexes:

- `(state,next_attempt_at,id)`.

### notification-service · taska.account_processed_events

Scope `GLOBAL_ACCOUNT`; retention `OUTBOX_REPLAY_SAFETY`. Explicit account event scope; never fallback sink for missing tenant IDs.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `consumer` `text` NOT NULL
- `event_id` `uuid` NOT NULL
- `processed_at` `timestamptz` NOT NULL

PK `(consumer, event_id)`.

### project-service · taska.tenant_work_partitions

Scope `SYSTEM`; retention `SYSTEM_CONFIGURATION`. Dispatcher only sees tenant UUID/due time; no actor/content/financial state. Workers select one partition then open tenant-scoped transaction, check durable authorization and fence. Domain row mutations enqueue partition in same local transaction; periodic tenant directory reconciler repairs missing wakeups.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `next_due_at` `timestamptz` NULL
- `lease_owner` `text` NULL
- `lease_expires_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(tenant_id)`.

CHECK:

- `version>0`.

Дополнительные indexes:

- `(next_due_at,tenant_id)`.

### workflow-service · taska.tenant_work_partitions

Scope `SYSTEM`; retention `SYSTEM_CONFIGURATION`. Dispatcher only sees tenant UUID/due time; no actor/content/financial state. Workers select one partition then open tenant-scoped transaction, check durable authorization and fence. Domain row mutations enqueue partition in same local transaction; periodic tenant directory reconciler repairs missing wakeups.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `next_due_at` `timestamptz` NULL
- `lease_owner` `text` NULL
- `lease_expires_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(tenant_id)`.

CHECK:

- `version>0`.

Дополнительные indexes:

- `(next_due_at,tenant_id)`.

### issue-service · taska.tenant_work_partitions

Scope `SYSTEM`; retention `SYSTEM_CONFIGURATION`. Dispatcher only sees tenant UUID/due time; no actor/content/financial state. Workers select one partition then open tenant-scoped transaction, check durable authorization and fence. Domain row mutations enqueue partition in same local transaction; periodic tenant directory reconciler repairs missing wakeups.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `next_due_at` `timestamptz` NULL
- `lease_owner` `text` NULL
- `lease_expires_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(tenant_id)`.

CHECK:

- `version>0`.

Дополнительные indexes:

- `(next_due_at,tenant_id)`.

### notification-service · taska.tenant_work_partitions

Scope `SYSTEM`; retention `SYSTEM_CONFIGURATION`. Dispatcher only sees tenant UUID/due time; no actor/content/financial state. Workers select one partition then open tenant-scoped transaction, check durable authorization and fence. Domain row mutations enqueue partition in same local transaction; periodic tenant directory reconciler repairs missing wakeups.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `next_due_at` `timestamptz` NULL
- `lease_owner` `text` NULL
- `lease_expires_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(tenant_id)`.

CHECK:

- `version>0`.

Дополнительные indexes:

- `(next_due_at,tenant_id)`.

### tenant-service · taska.tenant_work_partitions

Scope `SYSTEM`; retention `SYSTEM_CONFIGURATION`. Dispatcher only sees tenant UUID/due time; no actor/content/financial state. Workers select one partition then open tenant-scoped transaction, check durable authorization and fence. Domain row mutations enqueue partition in same local transaction; periodic tenant directory reconciler repairs missing wakeups.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `next_due_at` `timestamptz` NULL
- `lease_owner` `text` NULL
- `lease_expires_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(tenant_id)`.

CHECK:

- `version>0`.

Дополнительные indexes:

- `(next_due_at,tenant_id)`.

### billing-service · taska.tenant_work_partitions

Scope `SYSTEM`; retention `SYSTEM_CONFIGURATION`. Dispatcher only sees tenant UUID/due time; no actor/content/financial state. Workers select one partition then open tenant-scoped transaction, check durable authorization and fence. Domain row mutations enqueue partition in same local transaction; periodic tenant directory reconciler repairs missing wakeups.

ES_V1 mapping: `TENANT_WORKDATA` / `tenant_tasks_comments_history`; field/purpose overrides — target JSON.

Поля:

- `tenant_id` `uuid` NOT NULL
- `next_due_at` `timestamptz` NULL
- `lease_owner` `text` NULL
- `lease_expires_at` `timestamptz` NULL
- `version` `bigint` NOT NULL DEFAULT `1`

PK `(tenant_id)`.

CHECK:

- `version>0`.

Дополнительные indexes:

- `(next_due_at,tenant_id)`.

### tenant-service · taska.account_offboarding

Scope `GLOBAL_ACCOUNT`; retention `DELETED_PROFILE`. Admission barrier inside membership owner DB. All ownership/last-admin relevant commands acquire this user guard in sorted user-ID order before tenant lock; absent guard inserted idempotently and locked. Deletion sets ADMISSION_CLOSED before checking all memberships; owner assignment cannot race. Global account restriction bypass per D249 does not bypass this integrity fence. Every leave/remove/suspend/demotion command also locks the tenant and computes surviving ACTIVE TENANT_ADMIN users excluding guards in ADMISSION_CLOSED/REMOVING_MEMBERSHIPS/COMPLETED. This prevents another administrator leaving after deletion precheck and making the deleting user last-admin. Count runs after tenant lock on a fresh READ COMMITTED statement; guard creation/admission close precedes deletion membership prechecks.

ES_V1 mapping: `DELETED_PROFILE` / `deleted_account_profile`; field/purpose overrides — target JSON.

Поля:

- `user_id` `uuid` NOT NULL
- `operation_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `fence` `bigint` NOT NULL
- `version` `bigint` NOT NULL
- `started_at` `timestamptz` NOT NULL
- `completed_at` `timestamptz` NULL

PK `(user_id)`.

UNIQUE: `(operation_id)`.

CHECK:

- `state IN ('CHECKING','ADMISSION_CLOSED','REMOVING_MEMBERSHIPS','COMPLETED','CANCELLED')`.
- `fence>0 AND version>0`.

### tenant-service · taska.retention_holds

Scope `GLOBAL_CONTROL`; retention `PRIVACY_CASE`. ES_V1 scoped hold control plane, not an authorization grant. record_refs is a nonempty typed allowlist of exact owner/dataset/record or object-version references; no wildcard tenant dump or whole-backup hold. case_owner_ref and legal_basis identify accountable purpose, review_at at most 90 days after created_at/latest real review. Overdue keeps data restricted and escalates; it never auto-releases. Hold changes and irreversible purge admission serialize per referenced record; release is explicit authorized append-only case receipt, does not restart the original retention clock and cannot recover destroyed data. case_closed_at anchors minimal hold metadata retention under PRIVACY_CASE (case closure+3 calendar years), subject to evidenced legal extension. policy_version references ES_V1 configuration revision, not a mutable field inside financial facts.

ES_V1 mapping: `PRIVACY_CASE` / `deletion_evidence`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `subject_kind` `text` NOT NULL
- `subject_id` `uuid` NOT NULL
- `owner_service` `text` NOT NULL
- `reason_code` `text` NOT NULL
- `state` `text` NOT NULL
- `policy_key` `text` NOT NULL
- `policy_version` `bigint` NULL
- `version` `bigint` NOT NULL
- `created_by` `uuid` NOT NULL
- `created_at` `timestamptz` NOT NULL
- `resolved_by` `uuid` NULL
- `resolved_at` `timestamptz` NULL
- `case_id` `uuid` NOT NULL
- `record_refs` `jsonb` NOT NULL
- `legal_basis` `text[]` NOT NULL
- `case_owner_ref` `text` NOT NULL
- `last_reviewed_at` `timestamptz` NULL
- `review_at` `timestamptz` NOT NULL
- `case_closed_at` `timestamptz` NULL

PK `(id)`.

UNIQUE: `(case_id, subject_kind, subject_id, owner_service, reason_code)`.

CHECK:

- `subject_kind IN ('TENANT','ACCOUNT','FINANCIAL_FACT','AUDIT','FREE_MARKER')`.
- `state IN ('ACTIVE','RESOLVED')`.
- `version>0`.
- `jsonb_typeof(record_refs)='array' AND jsonb_array_length(record_refs)>0`.
- `cardinality(legal_basis)>0`.
- `length(btrim(case_owner_ref))>0`.
- `review_at>COALESCE(last_reviewed_at,created_at) AND review_at<=COALESCE(last_reviewed_at,created_at)+INTERVAL '90 days'`.
- `state<>'RESOLVED' OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL)`.

Дополнительные indexes:

- `(subject_kind,subject_id,state,version)`.

### billing-service · taska.invoice_payment_matches

Scope `TENANT`; retention `FINANCIAL_RECORD`. Append-only matching of one verified receipt to at most one invoice. Not final allocation, not paid coverage. Under ordered source/invoice locks, match min(unmatched available source amount, remaining invoice total); sum matches <= invoice total and each source amount. Match is permanent even after return; refund state excludes returned amounts from pending payable balance. Partial matched receipt remains unallocated principal until all invoice matches cover total. At expiry all matched-but-unallocated principal becomes RETURN_REQUIRED; excess never advances a future invoice. Matching an incorrect invoice is not edited in place: unresolved mistake enters settlement review, original evidence preserved.

ES_V1 mapping: `FINANCIAL_RECORD` / `ledger`; field/purpose overrides — target JSON.

Поля:

- `id` `uuid` NOT NULL
- `tenant_id` `uuid` NOT NULL
- `payment_fact_id` `uuid` NOT NULL
- `invoice_id` `uuid` NOT NULL
- `amount_minor` `bigint` NOT NULL
- `operation_id` `uuid` NOT NULL
- `matched_at` `timestamptz` NOT NULL

PK `(id)`.

UNIQUE: `(tenant_id, id)`; `(tenant_id, payment_fact_id)`; `(tenant_id, operation_id)`.

Локальные FK:

- `(tenant_id, payment_fact_id) → payment_facts(tenant_id, id) ON DELETE RESTRICT`.
- `(tenant_id, invoice_id) → invoices(tenant_id, id) ON DELETE RESTRICT`.

CHECK:

- `amount_minor>0`.

Дополнительные indexes:

- `(tenant_id,invoice_id,matched_at,id)`.

## Транзакционные инварианты, которые нельзя заменить одним CHECK

| Объект | Локальная транзакция и обязательная проверка |
|---|---|
| Company create | В tenant-service одним commit создать `tenants`, owner ACTIVE/TENANT_ADMIN membership, seat counter=1, operation и outbox. Deferred owner FK проверяется на commit. Для FREE в той же транзакции атомарно consumed marker; paid не расходует FREE. Billing provisioning — saga, состояние PROVISIONING до durable ack. |
| Последний администратор / owner | `SELECT tenants ... FOR UPDATE`; блокировать transfer/remove/suspend/leave/delete операции на tenant aggregate; проверить актуальные memberships. Нельзя удалить owner membership или последнего ACTIVE TENANT_ADMIN. FK существования owner не заменяет эти условия. |
| Account deletion | Auth выставляет durable deleting fence; tenant-service блокирует новые ownership/admin assignments через `account_offboarding`. Все такие команды блокируют user guard, затем tenant aggregate в фиксированном порядке UUID. Каждая leave/remove/suspend/demotion команда после tenant lock заново считает surviving ACTIVE TENANT_ADMIN, исключая accounts с ADMISSION_CLOSED/REMOVING_MEMBERSHIPS/COMPLETED. Поэтому другой администратор не может выйти после deletion precheck и оставить удаляемого пользователя последним. Проверяются все membership, а не только выбранный tenant; после закрытия admission повторяются owner/last-admin preconditions. |
| Invitation seat | Lock counter и invitation; reservation PENDING расходует одно место, ACTIVE/SUSPENDED membership — одно место. Acceptance переводит reserved→used один раз, не создаёт дополнительное место. Отзыв/expiry освобождает только активный резерв. Unique pending email исключает две параллельные заявки одной компании. Membership содержит приглашение только после принятия. |
| Quota admission | Только SEAT/PROJECT/STORAGE_BYTES: lock `(tenant_id,resource,scope_id)`; проверить `used+reserved+amount` без overflow по актуальной entitlement version, затем увеличить reserved и создать reservation. ADMIN bypass сохраняет тот же учёт. Предварительный SELECT/count в другом соединении недостаточен. |
| Resource commit | Сервис-владелец в одной SQL-транзакции создаёт ресурс, `resource_operation_receipts` и outbox. Tenant-service по receipt переносит reserved→used; unique quota_effect исключает повтор. Падение между локальным commit и ack устраняется сверкой по operation/reservation IDs. |
| Reservation timeout | `expires_at` запускает сверку, но не доказывает отсутствие committed resource. Сначала получить terminal receipt владельца или зафиксировать cancellation fence, который запрещает поздний commit; только затем RELEASE/EXPIRE. При недоступном владельце резерв удерживается, операция видима как pending reconciliation. |
| TASK_LIFETIME | Авторитетное поле `issue-service.project_counters.lifetime_created` увеличивается в одной транзакции с успешным create issue, idempotency result и outbox; удалённого ReserveQuota для него нет. Счётчик не равен issue-number и не уменьшается при удалении/завершении issue; считает paid-создания. Tenant-service хранит только read projection. Новый project ID получает новый counter; удаление всего проекта убирает его counter после terminal owner receipt. |
| Storage | RESERVED bytes→CONFIRMED bytes по HEAD и generation; фактический размер должен совпасть с допустимым reservation, расширение требует отдельного admission. S3 deletion failure не освобождает usage; повторный successful deletion receipt уменьшает used ровно один раз. Soft delete SQL-строки не равен удалению объекта. |
| Payment allocation | Lock invoice + payment fact в детерминированном порядке IDs. Сначала immutable `invoice_payment_matches`: один receipt связан максимум с одним invoice, matched amount не больше остатка invoice и доступного source principal. Недоплата отражает matching, остаётся UNALLOCATED и не создаёт coverage. Только при полной подходящей сумме создать по одному final allocation на match, PAID invoice, period и outbox в одной транзакции. `match_id` уникален; invoice/source/amount совпадают с match, selected eligibility evidence snapshot неизменяем. При expiry вернуть весь matched-but-unallocated principal; излишек не становится авансом. |
| Refund/chargeback | Lock source payment aggregate; admission нового исходящего возврата учитывает уже подтверждённые net returns и незавершённые reservations и не допускает расхода сверх principal. Реальный внешний факт всегда сохраняется, даже если внешняя гонка дала over-return; отрицательный вычисленный остаток вызывает NEEDS_REVIEW, а не отбрасывание/обрезание факта. Команда/approved/submitting/UNKNOWN не уменьшают coverage. Новая независимая оплата защищена отдельной allocation/period; старый refund её не отзывает. |
| Snapshot publication | После выпуска invoice запрещены изменения monetary/terms/seller/buyer/pricing columns. После publish catalog запрещены изменения версии. Append-only payment_facts/allocations. Реализация даёт отдельные insert/state-transition repository methods и DB guard triggers; app/admin прямой SQL UPDATE snapshots не разрешён. |
| Billing notice | Billing создаёт PENDING_INBOX; notification commit inbox и event-receipt возвращает `inboxCreatedAt`. Только этот факт фиксирует warning clock/effective boundary. Retry email не переносит дату и не создаёт второй notice. |
| Workflow / issue graph | Tenant FK не заменяет project ACL и проверку workflow ownership. Transition endpoints дополнительно связаны `(tenant,workflow,id)`. Comments/watchers/worklogs имеют `(tenant,project,issue)` FK. Issue links проверяют оба конца и их ACL; метки обязаны совпасть с project задачи. |
| Integration grant | Authoritative auth grant и текущая роль проверяются онлайн. Изменение grant targets/permissions транзакционно повышает version, отзывает старую область, публикует revocation. OAuth consent ключ содержит grant ID. USER не получает admin-функцию из одной global_role. |

## RLS: конкретные роли и административный путь

В baseline application.yml обычно задаёт default username `taska` (OAuth: `taska_oauth`), а admin read-only/outbox соединения имеют отдельные настройки с тем же fallback. Названия не доказывают ограниченные PostgreSQL privileges. Fresh-схемы baseline имеют RLS выключенным. Runtime роли/deployment здесь не проверялись.

Для каждой БД вводятся самостоятельные роли с подстановкой service short-name (`auth`, `tenant`, `billing`, `project`, `workflow`, `issue`, `notification`, `admin`, `oauth`):

| Роль | Доступ |
|---|---|
| `taska_<service>_migrator` | NOLOGIN владелец DDL; только deployment credential может SET ROLE. Не используется приложением/пулами/jobs. |
| `taska_<service>_app` | LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOINHERIT, NOBYPASSRLS; разрешённые repository DML. Не владелец таблиц. |
| `taska_<service>_admin` | Отдельный credential/pool, те же запреты. Только после verified admin authorization; tenant RLS действует. Read-only admin projections получают SELECT, mutations идут в owner service command. |
| `taska_<service>_job` | Отдельный worker pool, NOBYPASSRLS; одна tenant partition за транзакцию, service-specific DML, не административный пользователь. |
| `taska_<service>_dispatcher` | Только SELECT/lease UPDATE `tenant_work_partitions`; tenant IDs и due times, без business tables/payload. |

Ни одна runtime роль не член migrator/другой runtime роли; клиент не имеет прямого подключения к БД. Секреты пулов раздельны. RLS не защищает от компрометации самого владельца БД или произвольного кода backend; SQL injection и неаутентифицированный выбор admin pool должны отдельно предотвращаться контрактом.

Пример exact policy для tenant table:

```sql
ALTER TABLE taska.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.projects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_guard ON taska.projects
  TO taska_project_app, taska_project_admin, taska_project_job
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```

`tenants` сравнивает `id` вместо `tenant_id`; все остальные TENANT tables используют `tenant_id`. Невалидный UUID завершается ошибкой; missing/empty setting возвращает SQL NULL и не пропускает строки. Нет policy вида `tenant_id IS NULL OR ...` и нет переключателя `app.is_admin=true` для обхода RLS. JSON перечисляет глобальные таблицы; их разрешённые операции реализуются отдельными owner API/repository, а не fallback к tenant API.

Connection, transaction и контекст составляют одну область:

```sql
BEGIN;
SELECT set_config('app.tenant_id', :verified_tenant_id, true);
SELECT set_config('app.actor_id', :verified_actor_id, true);
-- SQL всегда дополнительно содержит WHERE tenant_id=:verified_tenant_id
COMMIT;
```

В R2DBC `set_config(..., true)` и business statements выполняются в одном TransactionalOperator на закреплённой connection. Setting запрещён вне транзакции; cleanup после rollback/commit/pool reuse проверяется IT. Отдельная connection для `SET` не имеет смысла.

Verified administrative path:

1. Auth подтверждает actor, текущий GLOBAL_ADMIN, web ADMIN_EMAIL_MFA либо явный ADMIN integration grant; обычный PAT/OAuth не повышается автоматически.
2. Domain authorization проверяет operation allowlist, READ/MODIFY, target tenant входит в TENANT_SET либо разрешён PLATFORM; global account command требует PLATFORM.
3. Server выбирает owner command/admin pool. Проверенный target tenant записывается transaction-local; межтенантный административный batch состоит из отдельных tenant transactions и отдельных results.
4. RLS по-прежнему ограничивает целевой tenant. Domain policy разрешает бизнес-bypass quotas/flags/read-only/pause/block, но проверяет целостность, idempotency, immutable IDs и невозможные функции (partial subscription refund, project move).
5. В том же owner commit сохраняются operation и audit event: реальный actor, канал, grant/version, target, request/operation IDs, reason, безопасный diff. Admin-service получает платформенный audit с dedupe; пользователи компании его не видят. Обычная issue history не подделывается.

Для перечисления компаний admin сначала получает UUID из отдельного авторизованного tenant directory API; content читается per tenant. Worker dispatcher видит только `tenant_work_partitions`, затем открывает scoped transaction. Глобальные catalog/config/identity operations имеют отдельные API/таблицы и никогда не выбираются отсутствием tenant в обычном запросе. Нужен audit cross-tenant directory lookup; выдача всех ID обычному app client запрещена.

## Cleanup и хранение — ES_V1

Утверждённая политика: [retention-policy.md](retention-policy.md), [ES_V1 JSON](schemas/retention-policy.json). Для каждой из 120 target tables JSON содержит `retention_policy_mapping`: policy/dataset и отдельные field/purpose overrides. Финансовые факты не получают mutable retention clock columns: срок выводится из authoritative accounting entry/corrections, применимости tax/OSS/claims и действующих scoped holds. Неизвестный fiscal anchor или applicability → POLICY_NOT_CONFIGURED, не догадка о шести годах для всех данных.

- FINANCIAL_RECORD — нормализованный финансовый реестр и необходимые evidence; TAX_OSS_RECORD/CONTRACT_CLAIM применяются только при установленной применимости. PRIVATE raw PSP body отдельно PROVIDER_RAW, не прикрывается длинным сроком ledger.
- Admin audit PURPOSE_AUDIT отделён от PRIVACY_CASE/financial approval evidence по назначению записи. Legal block не даёт обычного чтения, не обходится GLOBAL_ADMIN и не разрешает FREE matching.
- DELETED_PROFILE и TENANT_WORKDATA задают удаление своих active copies; удаление аккаунта не удаляет рабочую историю действующей компании. BACKUP сохраняет исходный capturedAt: повторная копия не перезапускает срок.
- FREE_ELIGIBILITY сохраняет связь каждого identity marker с живой компанией для FREE→paid→FREE. UNIQUE(bound_tenant_id) отсутствует: старый и новый владельцы могут иметь разные markers одной компании. При удалении аккаунта user-ref nullable; при удалении tenant очищаются tenant/user/operation refs всех его markers. Остаются только HMAC/key version/usedAt/purposeVersion/reviewDueAt. Нет восстановления из архива или сравнения после upheld erasure/purpose end.
- Secret/session/object/replay lifecycles остаются отдельными техническими ограничениями: legal retention не продлевает жизнь OTP/credentials/raw delivery secret. Минимальный ID fence хранится только до подтверждённого token/replay/unexpired-backup horizon, без копии профиля.

План удаления материализует immutable decision snapshot (`recordRefs`, policyVersion, sourceFactVersion/watermark, startEventAt, operationalUntil, eraseAfter, legalBasis, holdIds, disposition) в existing deletion step либо append-only RETENTION_* receipt admin_audit_log. Перед irreversible admission predicates и holds пересчитываются под локальной сериализацией владельца; snapshot и monetary fact не переписываются. Hold содержит caseId, точные recordRefs, legal basis, accountable owner и reviewAt не позже 90 дней от реального review; overdue не снимает hold. Минимальные hold metadata имеют PRIVACY_CASE anchor case_closed_at, а не время нового retry.

## Разделение подтверждённого и открытого

Дизайн и owner decisions D250–D253 от 03.11.2026 завершены: Stripe выбран, final partial chargeback отзывает только связанное покрытие без нового refund, WIRE return имеет SLA 5 рабочих дат с комиссией Taska, retention определён ES_V1. Готовность live требует фактические seller/Stripe merchant credentials, tax/currency/document settings и рабочий calendar; sandbox fixtures их не заменяют. WAITING_EVIDENCE относится к отсутствующим/противоречивым фактам, а не к уже закрытым C03/C05. Дополнительной самостоятельной аналитики разработчику не оставлено.

## Проверка и критерии готовности миграционных задач

`verify_data_inventory.py --postgres --target-postgres` повторяет actual SQL reconstruction и затем создаёт весь target DDL в отдельных disposable БД. Выполняются 10 RLS assertions: три роли app/admin/job × (missing-tenant sees zero, own tenant sees one, чужая запись отвергается), плюс transaction-local setting отсутствует после commit. Выполняются 20 CHECK probes (occurrence, payer, refund kind/reason, WIRE SLA и terminal dispute), 5 probes immutable SLA guard, 4 FREE binding/cleanup probes и 3 archive consume/append-only probes. Это подтверждает target DB форму, предложенные guard triggers и fail-closed RLS, но не реализацию production auth/RPC/admin pipeline или денежной saga.

После написания production migrations нужны свои IT: fresh и upgrade по принятому clean-start runbook; composite FK cross-tenant rejection; отсутствующий/чужой tenant/connection reuse; scopes на global tables; unique concurrent admission/owner transfer/last-admin; exactly-once resource/financial effect; rollback не меняет состояние; snapshot update denied. Проверки runtime и business effects не объявлены выполненными документационным DDL replay. Clean-start удаление старых тестовых данных, PR merge и deployment здесь не выполнялись и отдельно не авторизованы.

## Денежный баланс, недоплата и уточнение банковского evidence

В одной валюте для одного источника денег применяются определения:

```text
received              = verified PAYMENT principal
netAllocated          = gross immutable allocations - confirmed returns of allocated principal
unallocatedRemaining  = received - gross immutable allocations - confirmed returns of unallocated principal
confirmedNetReturned  = confirmed refunds + chargebacks - confirmed reversals
received              = netAllocated + unallocatedRemaining + confirmedNetReturned
```

`invoice_payment_matches` не входит в gross allocations и не добавляет деньги: это связь проверенного поступления с ожидающим счётом. При поступлении 12 EUR на invoice 20 EUR создаётся match 12, invoice PARTIALLY_PAID, allocation и period отсутствуют. Следующие 8 EUR добавляют второй match; при полной своевременной сумме одна транзакция создаёт две allocations 12+8 и один period. Если срок истёк после первых 12 EUR, все 12 остаются unallocated principal и переходят в RETURN_REQUIRED; после подтверждённого возврата баланс `12 = 0 + 0 + 12`.

При получении 25 EUR на invoice 20 EUR match/allocation составляют 20; излишек 5 возвращается как UNALLOCATED_RETURN/EXCESS. После возврата излишка `25 = 20 + 0 + 5`. После отдельного допустимого полного возврата покрытых 20 исходная allocation не переписывается, но netAllocated становится 0: `25 = 0 + 0 + 25`. API не предлагает произвольную часть SUBSCRIPTION_FULL; предшествующий внешний частичный возврат требует settlement policy, а не превращения оставшегося principal в разрешённую product partial refund.

Если PSP реально подтвердил внешний over-return, исходный факт не отбрасывается ради неотрицательного derived counter. Баланс сохраняет signed остаток и NEEDS_REVIEW; исходящие команды заблокированы до сверки. Нельзя смешивать проверку admission собственного расхода с записью внешнего финансового факта.

Банковское occurrence имеет `EXACT` либо `DAY`. Для EXACT обязательна только точная `occurred_at`, обе границы NULL. Для DAY `occurred_at` обязательно NULL, границы `[occurred_not_before,occurred_before)` обязательны и возрастают; они вычисляются из подтверждённых банковских календарных/timezone evidence, а не из произвольного UTC midnight. `observed_at` — время поступления информации в систему, `verified_at`/DTO confirmedAt — подтверждение оператором, ни одно не заменяет occurrence для deadline.

Для EXACT своевременность означает `occurred_at <= deadline`. Для DAY весь диапазон своевременен при `occurred_before <= deadline`, определённо поздний при `occurred_not_before > deadline`; остальные случаи, включая начало диапазона ровно в deadline, требуют WAITING_EVIDENCE. Для первой оплаты проверяется минимальный из invoice deadline и creation deadline. Точная дополнительная выписка создаёт append-only `FinancialEvidenceRefinement` в `settlement_cases.evidence_log`; она уточняет knowledge существующего fact, не создаёт новый денежный приход и не переписывает исходный DAY. Подтверждение выбирает проверенный `evidenceId`, а final allocation сохраняет неизменяемый `eligibility_evidence` snapshot выбранного основания. Повторы evidence/confirmation не дублируют деньги, allocation или period.

`autopay_mandates` сохраняет owner_id + owner_version и immutable consent snapshot/version/channel. `tenants.owner_version` увеличивается только при передаче владельца; проверка двух полей перед новым dispatch не позволяет старому mandate снова ожить при последовательности A→B→A. Смена имени компании сама mandate не инвалидирует.

## Дополнение 03.11: финальный dispute и immutable WIRE SLA

`LOST_PARTIAL_COVERAGE_REVOKED` — terminal outcome при `disputes.state=LOST`, с verified monetary loss и resolution evidence. Он отзывает только coverage этого source; независимое текущее покрытие сохраняется. Новый outgoing refund не создаётся. Для нового оплаченного месяца нужна новая полная оплата (PSP success / WIRE GLOBAL_ADMIN confirmedAt), старое autopay consent не восстанавливается. WON/WITHDRAWN_CONFIRMED не требуют выдуманного нового money fact, но требуют verified final resolution evidence.

`refunds.source_method` выводится из исходного факта. Для WIRE `fee_bearer=TASKA` обязателен уже при запросе. Первый полный комплект evidence атомарно фиксирует `evidence_completed_at`, `business_calendar_id`, `calendar_version`, `sla_policy_version=WIRE_RETURN_ES_V1`, typed `sla_snapshot`, `decision_due_at=send_due_at`. Календарь Europe/Madrid: пять eligible local dates после даты completeness, тот же local wall time; Mon–Fri без versioned Spanish national/seller regional/local bank nonworking days; DST gap→NEXT_VALID_INSTANT, overlap→LATER_OFFSET. Календарь/digest и deadline неизменяемы после фиксации; смена assignment, повтор, новая calendar version не перезапускают clock. Реальные decided_at/submitted_at могут быть позже срока — это breach, а не повод отклонить истинный факт. При необходимости оператор действует раньше неизменяемого SLA; отдельного hidden override deadline нет. Outbound fee учитывается отдельно от amount_minor principal и не уменьшает возврат.

## Operator archive case: durable consume существующим audit

TAS-482 использует локальный operator CLI и существующий `admin_audit_log`, без нового публичного API или таблицы. Добавлены nullable `case_id`, `permit_id`, `case_event_key` и обязательный `details_json` с закрытыми allowlists operator/RETENTION_* receipts. Raw OTP/permit secret/выписки/документы в JSON запрещены; только private evidence refs и hash.

`UNIQUE(case_id,case_event_key) WHERE case_id IS NOT NULL` исключает повтор receipt; `UNIQUE(permit_id) WHERE action='DELIVERY_PERMIT_CONSUMED'` обеспечивает одно потребление между процессами. Consume требует ненулевых case/permit IDs, совпадающих JSON bindings и обязательных hash/expiry/document/recipient/archive полей. После проверки текущего operator/MFA/case permission и срока 10 min транзакция вставляет consume receipt и **COMMIT до первого байта stream**. Конкурирующая вставка получает unique violation; in-memory lock не является доказательством одноразовости. Падение stream не отменяет consume: новая выдача требует нового проверенного permit. UPDATE audit receipt блокирует append-only guard; sanctioned retention erasure отделён от обычных app/admin grants.
