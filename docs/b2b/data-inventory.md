# Реестр текущих данных Taska — TAS-303

Источник: commit `af7ab57ead44304b89bbd47dabeeba45b761ce16`, все `db.changelog-master.yaml` семи владельцев БД. Состояние восстановлено применением SQL в порядке Liquibase include/includeAll на новой изолированной PostgreSQL 16. Это анализ кода и воспроизведённой fresh-схемы; фактический deployment, данные и права runtime не проверялись.

Машиночитаемый полный реестр: [inventory/tables.json](inventory/tables.json). Целевая схема: [target-data-model.md](target-data-model.md), [target-model.json](schemas/target-model.json). Production SQL не изменён.

## Объём и метод

| Владелец БД | SQL-файлов | Таблиц приложения | Колонок | Constraints | Индексов, включая PK/UNIQUE |
|---|---:|---:|---:|---:|---:|
| admin-service | 5 | 1 | 14 | 2 | 5 |
| auth-service | 14 | 10 | 88 | 43 | 36 |
| issue-service | 18 | 14 | 124 | 50 | 63 |
| notification-service | 14 | 9 | 83 | 27 | 25 |
| oauth-server | 1 | 5 | 54 | 6 | 12 |
| project-service | 9 | 8 | 91 | 32 | 23 |
| workflow-service | 5 | 8 | 72 | 28 | 22 |

Всего: **66 SQL-файлов, 55 прикладных таблиц, 526 колонок, 188 ограничений, 186 индексов**. 33 Java-модели с `@Table` и все явные `@Column` сверены с итоговой схемой. Таблицы, доступные через SQL/JDBC без `@Table`, включены наравне с ORM-моделями. Индексы PK/UNIQUE входят в число индексов и не являются дополнительными бизнес-ограничениями.

Проверка независимо читает миграционные masters, порядок файлов, `CREATE TABLE`, Java-аннотации, SHA-256 исходников и PostgreSQL `pg_catalog`. Она не выводит существование таблицы из самого JSON. PostgreSQL учитывает `ALTER`, `DROP`, переименование, типы, defaults, nullability, частичные/выраженные индексы, implicit FK/PK names. `users.login`, `credentials.locked_until` и удалённые индексы в итоговом реестре отсутствуют; `users.locked_until`, `blocked_from_status`, OAuth target/binding и свежие operation receipts присутствуют.

```sh
python3 docs/b2b/scripts/verify_data_inventory.py
python3 docs/b2b/scripts/verify_data_inventory.py --postgres
python3 docs/b2b/scripts/verify_data_inventory.py --target-postgres
# Осознанное обновление после анализа нового baseline:
python3 docs/b2b/scripts/verify_data_inventory.py --postgres --write
```

Для replay требуется локальный `postgres:16-alpine` и Docker. Скрипт создаёт собственный контейнер без сети, host ports и постоянных volumes, удаляет его в `finally`; чужую БД он не принимает как аргумент. Это проверка итоговой схемы свежей БД, не проверка исторического upgrade данных, Liquibase checksums или исполнения приложения. Служебные таблицы Liquibase ниже являются контрактом движка, а не результатом прямого SQL replay. Код не запускает Liquibase engine.

`source_commit` фиксирует этот аналитический baseline; при обновлении baseline его меняют явно вместе с review. Проверка без Docker проверяет hashes/полноту/явные model mappings; полная независимая сверка типов/ограничений/индексов — только `--postgres`. `--target-postgres` проверяет синтаксическую и ссылочную исполнимость целевой схемы и RLS probes; это не claim о реализованной бизнес-логике.

## Классы владения

В текущей схеме ни одна бизнес-таблица не имеет `tenant_id`; `TENANT_TARGET` в JSON — классификация для будущего преобразования, а не существующая изоляция. `GLOBAL_IDENTITY` — единый профиль/credentials/avatar; `GLOBAL_INTEGRATION` — зарегистрированные клиенты; `GRANT_SCOPED`/`MIXED_TARGET` требуют описанного в target разделения. `PLATFORM_AUDIT` недоступен tenant-ролям. `oauth_registration_lock` — системный singleton внутри прикладных миграций, отдельно от 14 engine-managed таблиц.

Все FK в реестре локальны владельцу БД. `user_id`, `project_id`, actor IDs и другие UUID без FK не следует считать доказательством текущей referential integrity через сервисы. Новая модель задаёт logical references и транзакционные проверки отдельно.

## Все таблицы приложения

### admin-service · taska.admin_audit_log

Класс: `PLATFORM_AUDIT`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `request_id` `text` NULL
- `actor_user_id` `uuid` NOT NULL
- `actor_display_name` `text` NOT NULL
- `actor_roles` `jsonb` NOT NULL
- `action` `text` NOT NULL
- `target_service` `text` NOT NULL
- `target_table` `text` NOT NULL
- `target_id` `text` NOT NULL
- `old_value` `jsonb` NULL
- `new_value` `jsonb` NULL
- `reason` `text` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `actor_email` `character varying(320)` NULL

Ограничения:

- `admin_audit_log_pkey`: `PRIMARY KEY (id)`
- `admin_audit_reason_chk`: `CHECK (length(TRIM(BOTH FROM reason)) > 0)`

Индексы:

- `CREATE INDEX admin_audit_log_actor_user_idx ON taska.admin_audit_log USING btree (actor_user_id)`
- `CREATE INDEX admin_audit_log_created_idx ON taska.admin_audit_log USING btree (created_at)`
- `CREATE UNIQUE INDEX admin_audit_log_pkey ON taska.admin_audit_log USING btree (id)`
- `CREATE INDEX admin_audit_log_request_idx ON taska.admin_audit_log USING btree (request_id)`
- `CREATE INDEX admin_audit_log_target_idx ON taska.admin_audit_log USING btree (target_service, target_table, target_id)`

Источники (создание, изменения и затрагивающие SQL):

- [admin-service/src/main/resources/db/changelog/changes/0001-admin-audit-log.sql](../../admin-service/src/main/resources/db/changelog/changes/0001-admin-audit-log.sql)
- [admin-service/src/main/resources/db/changelog/changes/0002-admin-audit-log-target-service-keys.sql](../../admin-service/src/main/resources/db/changelog/changes/0002-admin-audit-log-target-service-keys.sql)
- [admin-service/src/main/resources/db/changelog/changes/0003-audit-actor-identity.sql](../../admin-service/src/main/resources/db/changelog/changes/0003-audit-actor-identity.sql)
- [admin-service/src/main/resources/db/changelog/changes/0004-mask-audit-outbox-retry.sql](../../admin-service/src/main/resources/db/changelog/changes/0004-mask-audit-outbox-retry.sql)

### auth-service · taska.credentials

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `user_id` `uuid` NOT NULL
- `credential_type` `character varying(32)` NOT NULL
- `provider` `character varying(64)` NULL
- `subject` `character varying(256)` NULL
- `secret_hash` `character varying(255)` NULL
- `algo` `character varying(32)` NULL
- `meta` `jsonb` NULL
- `failed_attempts` `integer` NOT NULL DEFAULT `0`
- `last_failed_at` `timestamp with time zone` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `credentials_algo_chk`: `CHECK (algo IS NULL OR (algo::text = ANY (ARRAY['BCRYPT'::character varying, 'ARGON2'::character varying]::text[])))`
- `credentials_failed_attempts_chk`: `CHECK (failed_attempts >= 0)`
- `credentials_pkey`: `PRIMARY KEY (id)`
- `credentials_shape_chk`: `CHECK (credential_type::text = 'PASSWORD'::text AND provider IS NULL AND subject IS NULL AND secret_hash IS NOT NULL AND algo IS NOT NULL OR (credential_type::text = ANY (ARRAY['OAUTH'::character varying, 'OIDC'::character varying, 'SAML'::character varying]::text[])) AND provider IS NOT NULL AND subject IS NOT NULL AND secret_hash IS NULL OR credential_type::text = 'API_TOKEN'::text AND secret_hash IS NOT NULL AND provider IS NULL AND subject IS NULL OR credential_type::text = 'WEBAUTHN'::text)`
- `credentials_type_chk`: `CHECK (credential_type::text = ANY (ARRAY['PASSWORD'::character varying, 'OAUTH'::character varying, 'OIDC'::character varying, 'SAML'::character varying, 'API_TOKEN'::character varying, 'WEBAUTHN'::character varying]::text[]))`
- `credentials_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE`

Индексы:

- `CREATE UNIQUE INDEX credentials_external_uniq ON taska.credentials USING btree (credential_type, provider, subject) WHERE (((credential_type)::text = ANY ((ARRAY['OAUTH'::character varying, 'OIDC'::character varying, 'SAML'::character varying])::text[])) AND (provider IS NOT NULL) AND (subject IS NOT NULL))`
- `CREATE UNIQUE INDEX credentials_pkey ON taska.credentials USING btree (id)`
- `CREATE UNIQUE INDEX credentials_user_password_uniq ON taska.credentials USING btree (user_id) WHERE ((credential_type)::text = 'PASSWORD'::text)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0000-init.sql](../../auth-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [auth-service/src/main/resources/db/changelog/changes/0004-user-locked-until.sql](../../auth-service/src/main/resources/db/changelog/changes/0004-user-locked-until.sql)
- [auth-service/src/main/resources/db/changelog/changes/0011-blocked-from-status.sql](../../auth-service/src/main/resources/db/changelog/changes/0011-blocked-from-status.sql)

### auth-service · taska.invite_tokens

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `user_id` `uuid` NOT NULL
- `token_hash` `character varying(255)` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `expires_at` `timestamp with time zone` NOT NULL
- `used_at` `timestamp with time zone` NULL

Ограничения:

- `invite_tokens_expires_after_created_chk`: `CHECK (expires_at > created_at)`
- `invite_tokens_pkey`: `PRIMARY KEY (id)`
- `invite_tokens_token_hash_key`: `UNIQUE (token_hash)`
- `invite_tokens_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE`

Индексы:

- `CREATE INDEX invite_tokens_expires_idx ON taska.invite_tokens USING btree (expires_at)`
- `CREATE UNIQUE INDEX invite_tokens_pkey ON taska.invite_tokens USING btree (id)`
- `CREATE UNIQUE INDEX invite_tokens_token_hash_key ON taska.invite_tokens USING btree (token_hash)`
- `CREATE INDEX invite_tokens_used_idx ON taska.invite_tokens USING btree (used_at)`
- `CREATE INDEX invite_tokens_user_idx ON taska.invite_tokens USING btree (user_id)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0000-init.sql](../../auth-service/src/main/resources/db/changelog/changes/0000-init.sql)

### auth-service · taska.oauth_grants

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `code_hash` `character(64)` NOT NULL
- `kind` `character varying(10)` NOT NULL
- `provider` `character varying(10)` NOT NULL
- `subject` `character varying(512)` NOT NULL
- `email` `character varying(320)` NOT NULL
- `suggested_name` `character varying(128)` NULL
- `return_to` `character varying(1024)` NOT NULL
- `expires_at` `timestamp with time zone` NOT NULL
- `target` `character varying(8)` NOT NULL DEFAULT `'ui'::character varying`
- `binding` `character(43)` NULL

Ограничения:

- `oauth_grant_target_binding`: `CHECK (target::text = 'ui'::text AND binding IS NULL OR target::text = 'oauth'::text AND kind::text = 'EXCHANGE'::text AND binding IS NOT NULL AND binding ~ '^[A-Za-z0-9_-]{43}$'::text)`
- `oauth_grants_kind_check`: `CHECK (kind::text = ANY (ARRAY['EXCHANGE'::character varying, 'SIGNUP'::character varying]::text[]))`
- `oauth_grants_pkey`: `PRIMARY KEY (code_hash)`

Индексы:

- `CREATE INDEX oauth_grants_expiry_idx ON taska.oauth_grants USING btree (expires_at)`
- `CREATE UNIQUE INDEX oauth_grants_pkey ON taska.oauth_grants USING btree (code_hash)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0010-oauth-flows.sql](../../auth-service/src/main/resources/db/changelog/changes/0010-oauth-flows.sql)
- [auth-service/src/main/resources/db/changelog/changes/0012-oauth-sign-in-target.sql](../../auth-service/src/main/resources/db/changelog/changes/0012-oauth-sign-in-target.sql)

### auth-service · taska.oauth_states

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `state_hash` `character(64)` NOT NULL
- `browser_hash` `character(64)` NOT NULL
- `provider` `character varying(10)` NOT NULL
- `nonce` `character varying(128)` NOT NULL
- `verifier` `character varying(128)` NOT NULL
- `return_to` `character varying(1024)` NOT NULL
- `expires_at` `timestamp with time zone` NOT NULL
- `target` `character varying(8)` NOT NULL DEFAULT `'ui'::character varying`
- `binding` `character(43)` NULL

Ограничения:

- `oauth_state_target_binding`: `CHECK (target::text = 'ui'::text AND binding IS NULL OR target::text = 'oauth'::text AND binding IS NOT NULL AND binding ~ '^[A-Za-z0-9_-]{43}$'::text)`
- `oauth_states_pkey`: `PRIMARY KEY (state_hash)`

Индексы:

- `CREATE INDEX oauth_states_expiry_idx ON taska.oauth_states USING btree (expires_at)`
- `CREATE UNIQUE INDEX oauth_states_pkey ON taska.oauth_states USING btree (state_hash)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0010-oauth-flows.sql](../../auth-service/src/main/resources/db/changelog/changes/0010-oauth-flows.sql)
- [auth-service/src/main/resources/db/changelog/changes/0012-oauth-sign-in-target.sql](../../auth-service/src/main/resources/db/changelog/changes/0012-oauth-sign-in-target.sql)

### auth-service · taska.outbox_events

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `uuid` NOT NULL
- `event_type` `text` NOT NULL
- `payload` `jsonb` NOT NULL
- `status` `text` NOT NULL DEFAULT `'NEW'::text`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `published_at` `timestamp with time zone` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `last_error_message` `text` NULL
- `processing_started_at` `timestamp with time zone` NULL
- `request_id` `text` NULL

Ограничения:

- `outbox_events_attempts_chk`: `CHECK (attempts >= 0)`
- `outbox_events_pkey`: `PRIMARY KEY (id)`
- `outbox_events_status_chk`: `CHECK (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text, 'PUBLISHED'::text, 'FAILED'::text]))`

Индексы:

- `CREATE INDEX outbox_events_aggregate_idx ON taska.outbox_events USING btree (aggregate_type, aggregate_id)`
- `CREATE UNIQUE INDEX outbox_events_pkey ON taska.outbox_events USING btree (id)`
- `CREATE INDEX outbox_events_processing_idx ON taska.outbox_events USING btree (status, processing_started_at) WHERE ((status = 'PROCESSING'::text) AND (processing_started_at IS NOT NULL))`
- `CREATE INDEX outbox_events_status_created_idx ON taska.outbox_events USING btree (status, created_at) WHERE (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text]))`
- `CREATE INDEX outbox_events_unpublished_idx ON taska.outbox_events USING btree (created_at) WHERE (published_at IS NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0000-init.sql](../../auth-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [auth-service/src/main/resources/db/changelog/changes/0008-notification-email-events.sql](../../auth-service/src/main/resources/db/changelog/changes/0008-notification-email-events.sql)

### auth-service · taska.personal_access_tokens

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `user_id` `uuid` NOT NULL
- `name` `character varying(100)` NOT NULL
- `token_prefix` `character varying(16)` NOT NULL
- `token_hash` `character(64)` NOT NULL
- `scopes` `text[]` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL
- `expires_at` `timestamp with time zone` NOT NULL
- `last_used_at` `timestamp with time zone` NULL
- `revoked_at` `timestamp with time zone` NULL
- `revoked_reason` `character varying(64)` NULL

Ограничения:

- `personal_access_tokens_check`: `CHECK (expires_at > created_at)`
- `personal_access_tokens_check1`: `CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))`
- `personal_access_tokens_name_check`: `CHECK (length(btrim(name::text)) >= 1 AND length(btrim(name::text)) <= 100)`
- `personal_access_tokens_pkey`: `PRIMARY KEY (id)`
- `personal_access_tokens_scopes_check`: `CHECK (scopes = ARRAY['read'::text] OR scopes = ARRAY['read'::text, 'write'::text])`
- `personal_access_tokens_token_hash_check`: `CHECK (token_hash ~ '^[0-9a-f]{64}$'::text)`
- `personal_access_tokens_token_hash_key`: `UNIQUE (token_hash)`
- `personal_access_tokens_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE`

Индексы:

- `CREATE INDEX personal_access_tokens_owner_idx ON taska.personal_access_tokens USING btree (user_id, created_at DESC, id DESC)`
- `CREATE UNIQUE INDEX personal_access_tokens_pkey ON taska.personal_access_tokens USING btree (id)`
- `CREATE UNIQUE INDEX personal_access_tokens_token_hash_key ON taska.personal_access_tokens USING btree (token_hash)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0008-personal-access-tokens.sql](../../auth-service/src/main/resources/db/changelog/changes/0008-personal-access-tokens.sql)

### auth-service · taska.refresh_tokens

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `user_id` `uuid` NOT NULL
- `token_hash` `character varying(255)` NOT NULL
- `issued_at` `timestamp with time zone` NOT NULL
- `expires_at` `timestamp with time zone` NOT NULL
- `revoked_at` `timestamp with time zone` NULL
- `replaced_by` `uuid` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `refresh_tokens_expires_after_issued_chk`: `CHECK (expires_at > issued_at)`
- `refresh_tokens_pkey`: `PRIMARY KEY (id)`
- `refresh_tokens_replaced_by_fkey`: `FOREIGN KEY (replaced_by) REFERENCES taska.refresh_tokens(id) ON DELETE SET NULL`
- `refresh_tokens_token_hash_key`: `UNIQUE (token_hash)`
- `refresh_tokens_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE`

Индексы:

- `CREATE INDEX refresh_tokens_expires_idx ON taska.refresh_tokens USING btree (expires_at)`
- `CREATE UNIQUE INDEX refresh_tokens_pkey ON taska.refresh_tokens USING btree (id)`
- `CREATE INDEX refresh_tokens_replaced_idx ON taska.refresh_tokens USING btree (replaced_by)`
- `CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON taska.refresh_tokens USING btree (token_hash)`
- `CREATE INDEX refresh_tokens_user_idx ON taska.refresh_tokens USING btree (user_id)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0000-init.sql](../../auth-service/src/main/resources/db/changelog/changes/0000-init.sql)

### auth-service · taska.signup_rate_limits

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `bucket` `text` NOT NULL
- `started_at` `timestamp with time zone` NOT NULL
- `attempts` `integer` NOT NULL

Ограничения:

- `signup_rate_limits_attempts_check`: `CHECK (attempts > 0)`
- `signup_rate_limits_pkey`: `PRIMARY KEY (bucket)`

Индексы:

- `CREATE INDEX signup_rate_limits_cleanup_idx ON taska.signup_rate_limits USING btree (started_at)`
- `CREATE UNIQUE INDEX signup_rate_limits_pkey ON taska.signup_rate_limits USING btree (bucket)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0010-signup-rate-limits.sql](../../auth-service/src/main/resources/db/changelog/changes/0010-signup-rate-limits.sql)

### auth-service · taska.user_avatars

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `user_id` `uuid` NOT NULL
- `object_key` `character varying(512)` NOT NULL
- `file_name` `character varying(255)` NOT NULL
- `content_type` `character varying(128)` NOT NULL
- `size_bytes` `bigint` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL

Ограничения:

- `user_avatars_pkey`: `PRIMARY KEY (id)`
- `user_avatars_size_positive_chk`: `CHECK (size_bytes > 0)`
- `user_avatars_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES taska.users(id) ON DELETE CASCADE`
- `user_avatars_user_id_uq`: `UNIQUE (user_id)`

Индексы:

- `CREATE UNIQUE INDEX user_avatars_object_key_uq ON taska.user_avatars USING btree (object_key)`
- `CREATE UNIQUE INDEX user_avatars_pkey ON taska.user_avatars USING btree (id)`
- `CREATE UNIQUE INDEX user_avatars_user_id_uq ON taska.user_avatars USING btree (user_id)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0002-user-avatars.sql](../../auth-service/src/main/resources/db/changelog/changes/0002-user-avatars.sql)
- [auth-service/src/main/resources/db/changelog/changes/0005-avatar-object-key-unique.sql](../../auth-service/src/main/resources/db/changelog/changes/0005-avatar-object-key-unique.sql)

### auth-service · taska.users

Класс: `GLOBAL_IDENTITY`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `global_role` `character varying(32)` NOT NULL DEFAULT `'USER'::character varying`
- `email` `character varying(320)` NOT NULL
- `display_name` `character varying(128)` NOT NULL
- `status` `character varying(32)` NOT NULL DEFAULT `'INVITED'::character varying`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `locked_until` `timestamp with time zone` NULL
- `created_via` `character varying(16)` NOT NULL DEFAULT `'INVITATION'::character varying`
- `blocked_from_status` `character varying(16)` NULL

Ограничения:

- `global_role_chk`: `CHECK (global_role::text = ANY (ARRAY['USER'::character varying, 'GLOBAL_ADMIN'::character varying]::text[]))`
- `users_blocked_from_status_chk`: `CHECK (blocked_from_status::text = ANY (ARRAY['INVITED'::character varying, 'ACTIVE'::character varying]::text[]))`
- `users_created_via_chk`: `CHECK (created_via::text = ANY (ARRAY['INVITATION'::character varying, 'SIGNUP'::character varying, 'OAUTH'::character varying]::text[]))`
- `users_email_key`: `UNIQUE (email)`
- `users_pkey`: `PRIMARY KEY (id)`
- `users_status_chk`: `CHECK (status::text = ANY (ARRAY['INVITED'::character varying, 'ACTIVE'::character varying, 'BLOCKED'::character varying, 'LOCKED'::character varying]::text[]))`

Индексы:

- `CREATE INDEX users_directory_email_trgm_idx ON taska.users USING gin (lower((email)::text) gin_trgm_ops)`
- `CREATE INDEX users_directory_name_trgm_idx ON taska.users USING gin (lower((display_name)::text) gin_trgm_ops)`
- `CREATE UNIQUE INDEX users_email_canonical_uq ON taska.users USING btree (lower(btrim((email)::text)))`
- `CREATE UNIQUE INDEX users_email_key ON taska.users USING btree (email)`
- `CREATE UNIQUE INDEX users_pkey ON taska.users USING btree (id)`
- `CREATE INDEX users_status_idx ON taska.users USING btree (status)`

Источники (создание, изменения и затрагивающие SQL):

- [auth-service/src/main/resources/db/changelog/changes/0000-init.sql](../../auth-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [auth-service/src/main/resources/db/changelog/changes/0002-user-avatars.sql](../../auth-service/src/main/resources/db/changelog/changes/0002-user-avatars.sql)
- [auth-service/src/main/resources/db/changelog/changes/0003-user-status-locked.sql](../../auth-service/src/main/resources/db/changelog/changes/0003-user-status-locked.sql)
- [auth-service/src/main/resources/db/changelog/changes/0004-user-locked-until.sql](../../auth-service/src/main/resources/db/changelog/changes/0004-user-locked-until.sql)
- [auth-service/src/main/resources/db/changelog/changes/0006-directory-email.sql](../../auth-service/src/main/resources/db/changelog/changes/0006-directory-email.sql)
- [auth-service/src/main/resources/db/changelog/changes/0007-remove-user-login.sql](../../auth-service/src/main/resources/db/changelog/changes/0007-remove-user-login.sql)
- [auth-service/src/main/resources/db/changelog/changes/0008-notification-email-events.sql](../../auth-service/src/main/resources/db/changelog/changes/0008-notification-email-events.sql)
- [auth-service/src/main/resources/db/changelog/changes/0008-personal-access-tokens.sql](../../auth-service/src/main/resources/db/changelog/changes/0008-personal-access-tokens.sql)
- [auth-service/src/main/resources/db/changelog/changes/0009-user-created-via.sql](../../auth-service/src/main/resources/db/changelog/changes/0009-user-created-via.sql)
- [auth-service/src/main/resources/db/changelog/changes/0011-blocked-from-status.sql](../../auth-service/src/main/resources/db/changelog/changes/0011-blocked-from-status.sql)

### issue-service · taska.idempotency_keys

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `key` `text` NOT NULL
- `user_id` `uuid` NOT NULL
- `request_hash` `text` NOT NULL
- `response` `jsonb` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `expires_at` `timestamp with time zone` NOT NULL

Ограничения:

- `idempotency_keys_pkey`: `PRIMARY KEY (id)`
- `idempotency_keys_user_key_uniq`: `UNIQUE (user_id, key)`

Индексы:

- `CREATE INDEX idempotency_keys_expires_at_idx ON taska.idempotency_keys USING btree (expires_at)`
- `CREATE UNIQUE INDEX idempotency_keys_pkey ON taska.idempotency_keys USING btree (id)`
- `CREATE UNIQUE INDEX idempotency_keys_user_key_uniq ON taska.idempotency_keys USING btree (user_id, key)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0000-init.sql](../../issue-service/src/main/resources/db/changelog/changes/0000-init.sql)

### issue-service · taska.issue_attachments

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `issue_id` `uuid` NOT NULL
- `uploaded_by` `uuid` NOT NULL
- `object_key` `text` NOT NULL
- `file_name` `text` NOT NULL
- `content_type` `text` NOT NULL
- `size_bytes` `bigint` NOT NULL
- `checksum` `text` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `deleted_at` `timestamp with time zone` NULL

Ограничения:

- `fk_issue_attachments_issue`: `FOREIGN KEY (issue_id) REFERENCES taska.issues(id)`
- `issue_attachments_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE UNIQUE INDEX issue_attachments_pkey ON taska.issue_attachments USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0001-issue-attachments.sql](../../issue-service/src/main/resources/db/changelog/changes/0001-issue-attachments.sql)

### issue-service · taska.issue_comments

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `issue_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `author_user_id` `uuid` NOT NULL
- `body` `text` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `deleted_at` `timestamp with time zone` NULL
- `version` `integer` NOT NULL DEFAULT `1`
- `channel` `character varying(32)` NULL
- `token_id` `uuid` NULL

Ограничения:

- `issue_comments_channel_chk`: `CHECK (channel IS NULL OR (channel::text = ANY (ARRAY['UI'::character varying, 'API_TOKEN'::character varying, 'MCP'::character varying]::text[])))`
- `issue_comments_issue_id_fkey`: `FOREIGN KEY (issue_id) REFERENCES taska.issues(id) ON DELETE CASCADE`
- `issue_comments_pkey`: `PRIMARY KEY (id)`
- `issue_comments_version_chk`: `CHECK (version >= 1)`

Индексы:

- `CREATE INDEX issue_comments_author_user_id_idx ON taska.issue_comments USING btree (author_user_id, created_at DESC) WHERE (deleted_at IS NULL)`
- `CREATE INDEX issue_comments_issue_created_idx ON taska.issue_comments USING btree (issue_id, created_at DESC)`
- `CREATE UNIQUE INDEX issue_comments_pkey ON taska.issue_comments USING btree (id)`
- `CREATE INDEX issue_comments_project_id_idx ON taska.issue_comments USING btree (project_id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0003-add-issue-comments.sql](../../issue-service/src/main/resources/db/changelog/changes/0003-add-issue-comments.sql)
- [issue-service/src/main/resources/db/changelog/changes/0011-action-channel.sql](../../issue-service/src/main/resources/db/changelog/changes/0011-action-channel.sql)

### issue-service · taska.issue_history

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `issue_id` `uuid` NOT NULL
- `event_type` `text` NOT NULL
- `actor_user_id` `uuid` NOT NULL
- `occurred_at` `timestamp with time zone` NOT NULL
- `payload` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `channel` `character varying(32)` NULL
- `token_id` `uuid` NULL

Ограничения:

- `issue_history_channel_chk`: `CHECK (channel IS NULL OR (channel::text = ANY (ARRAY['UI'::character varying, 'API_TOKEN'::character varying, 'MCP'::character varying]::text[])))`
- `issue_history_event_type_chk`: `CHECK (event_type = ANY (ARRAY['CREATED'::text, 'UPDATED'::text, 'ASSIGNED'::text, 'TRANSITIONED'::text, 'DELETED'::text, 'LINK_CREATED'::text, 'LINK_DELETED'::text, 'ATTACHMENT_UPLOADED'::text, 'ATTACHMENT_DELETED'::text, 'COMMENT_CREATED'::text, 'COMMENT_UPDATED'::text, 'COMMENT_DELETED'::text, 'LABEL_ADDED'::text, 'LABEL_REMOVED'::text, 'WORKLOG_ADDED'::text, 'WORKLOG_UPDATED'::text, 'WORKLOG_DELETED'::text]))`
- `issue_history_issue_id_fkey`: `FOREIGN KEY (issue_id) REFERENCES taska.issues(id) ON DELETE CASCADE`
- `issue_history_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE INDEX issue_history_actor_occurred_idx ON taska.issue_history USING btree (actor_user_id, occurred_at)`
- `CREATE INDEX issue_history_issue_occurred_idx ON taska.issue_history USING btree (issue_id, occurred_at)`
- `CREATE UNIQUE INDEX issue_history_pkey ON taska.issue_history USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0000-init.sql](../../issue-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [issue-service/src/main/resources/db/changelog/changes/0002-update-issue-history-event-types.sql](../../issue-service/src/main/resources/db/changelog/changes/0002-update-issue-history-event-types.sql)
- [issue-service/src/main/resources/db/changelog/changes/0003-add-issue-comments.sql](../../issue-service/src/main/resources/db/changelog/changes/0003-add-issue-comments.sql)
- [issue-service/src/main/resources/db/changelog/changes/0004-issue-links-modify.sql](../../issue-service/src/main/resources/db/changelog/changes/0004-issue-links-modify.sql)
- [issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql)
- [issue-service/src/main/resources/db/changelog/changes/0008-add-issue-worklogs.sql](../../issue-service/src/main/resources/db/changelog/changes/0008-add-issue-worklogs.sql)
- [issue-service/src/main/resources/db/changelog/changes/0009-redact-comment-event-text.sql](../../issue-service/src/main/resources/db/changelog/changes/0009-redact-comment-event-text.sql)
- [issue-service/src/main/resources/db/changelog/changes/0011-action-channel.sql](../../issue-service/src/main/resources/db/changelog/changes/0011-action-channel.sql)

### issue-service · taska.issue_labels

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `issue_id` `uuid` NOT NULL
- `label_id` `uuid` NOT NULL
- `created_by` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `issue_labels_issue_id_fkey`: `FOREIGN KEY (issue_id) REFERENCES taska.issues(id) ON DELETE CASCADE`
- `issue_labels_label_id_fkey`: `FOREIGN KEY (label_id) REFERENCES taska.project_labels(id) ON DELETE CASCADE`
- `issue_labels_pkey`: `PRIMARY KEY (id)`
- `unique_issue_label_pair`: `UNIQUE (issue_id, label_id)`

Индексы:

- `CREATE INDEX idx_issue_labels_label_id ON taska.issue_labels USING btree (label_id)`
- `CREATE UNIQUE INDEX issue_labels_pkey ON taska.issue_labels USING btree (id)`
- `CREATE UNIQUE INDEX unique_issue_label_pair ON taska.issue_labels USING btree (issue_id, label_id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql)

### issue-service · taska.issue_links

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `source_issue_id` `uuid` NOT NULL
- `target_issue_id` `uuid` NOT NULL
- `link_type` `text` NOT NULL
- `created_by` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `project_id` `uuid` NULL
- `deleted_at` `timestamp with time zone` NULL

Ограничения:

- `issue_links_link_type_chk`: `CHECK (link_type = ANY (ARRAY['BLOCKS'::text, 'RELATES_TO'::text, 'DUPLICATES'::text]))`
- `issue_links_pkey`: `PRIMARY KEY (id)`
- `issue_links_source_issue_id_fkey`: `FOREIGN KEY (source_issue_id) REFERENCES taska.issues(id) ON DELETE CASCADE`
- `issue_links_target_issue_id_fkey`: `FOREIGN KEY (target_issue_id) REFERENCES taska.issues(id) ON DELETE CASCADE`

Индексы:

- `CREATE INDEX issue_links_active_source_idx ON taska.issue_links USING btree (source_issue_id) WHERE (deleted_at IS NULL)`
- `CREATE INDEX issue_links_active_target_idx ON taska.issue_links USING btree (target_issue_id) WHERE (deleted_at IS NULL)`
- `CREATE UNIQUE INDEX issue_links_directed_unique_active_idx ON taska.issue_links USING btree (source_issue_id, target_issue_id, link_type) WHERE ((deleted_at IS NULL) AND (link_type = ANY (ARRAY['BLOCKS'::text, 'DUPLICATES'::text])))`
- `CREATE UNIQUE INDEX issue_links_pkey ON taska.issue_links USING btree (id)`
- `CREATE UNIQUE INDEX issue_links_relates_unique_active_idx ON taska.issue_links USING btree (LEAST(source_issue_id, target_issue_id), GREATEST(source_issue_id, target_issue_id)) WHERE ((deleted_at IS NULL) AND (link_type = 'RELATES_TO'::text))`
- `CREATE INDEX issue_links_source_issue_idx ON taska.issue_links USING btree (source_issue_id)`
- `CREATE INDEX issue_links_target_issue_idx ON taska.issue_links USING btree (target_issue_id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0000-init.sql](../../issue-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [issue-service/src/main/resources/db/changelog/changes/0004-issue-links-modify.sql](../../issue-service/src/main/resources/db/changelog/changes/0004-issue-links-modify.sql)

### issue-service · taska.issue_type_move_receipts

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `operation_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `source_code` `text` NOT NULL
- `source_generation` `uuid` NOT NULL
- `target_code` `text` NULL
- `target_generation` `uuid` NULL
- `state` `text` NOT NULL
- `moved_count` `bigint` NOT NULL DEFAULT `0`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `issue_type_move_receipts_moved_count_check`: `CHECK (moved_count >= 0)`
- `issue_type_move_receipts_pkey`: `PRIMARY KEY (operation_id)`
- `issue_type_move_receipts_state_check`: `CHECK (state = ANY (ARRAY['APPLIED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE UNIQUE INDEX issue_type_move_receipts_pkey ON taska.issue_type_move_receipts USING btree (operation_id)`
- `CREATE INDEX issue_type_write_tombstone_idx ON taska.issue_type_move_receipts USING btree (project_id, source_code, source_generation) WHERE (state = 'APPLIED'::text)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0013-issue-type-move-receipts.sql](../../issue-service/src/main/resources/db/changelog/changes/0013-issue-type-move-receipts.sql)

### issue-service · taska.issue_watchers

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `issue_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `created_by` `uuid` NOT NULL

Ограничения:

- `issue_watchers_issue_id_fkey`: `FOREIGN KEY (issue_id) REFERENCES taska.issues(id) ON DELETE CASCADE`
- `issue_watchers_issue_user_uniq`: `UNIQUE (issue_id, user_id)`
- `issue_watchers_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE UNIQUE INDEX issue_watchers_issue_user_uniq ON taska.issue_watchers USING btree (issue_id, user_id)`
- `CREATE UNIQUE INDEX issue_watchers_pkey ON taska.issue_watchers USING btree (id)`
- `CREATE INDEX issue_watchers_project_id_idx ON taska.issue_watchers USING btree (project_id)`
- `CREATE INDEX issue_watchers_user_id_idx ON taska.issue_watchers USING btree (user_id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0005-add-issue-watchers.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-add-issue-watchers.sql)

### issue-service · taska.issue_worklogs

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `issue_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `author_user_id` `uuid` NOT NULL
- `spent_minutes` `integer` NOT NULL
- `work_date` `date` NOT NULL
- `comment` `text` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `deleted_at` `timestamp with time zone` NULL
- `version` `integer` NOT NULL DEFAULT `1`

Ограничения:

- `issue_worklogs_pkey`: `PRIMARY KEY (id)`
- `issue_worklogs_spent_minutes_chk`: `CHECK (spent_minutes > 0)`
- `issue_worklogs_version_chk`: `CHECK (version > 0)`

Индексы:

- `CREATE INDEX issue_worklogs_author_id_active_idx ON taska.issue_worklogs USING btree (author_user_id) WHERE (deleted_at IS NULL)`
- `CREATE INDEX issue_worklogs_issue_page_active_idx ON taska.issue_worklogs USING btree (issue_id, work_date DESC, created_at DESC, id DESC) WHERE (deleted_at IS NULL)`
- `CREATE UNIQUE INDEX issue_worklogs_pkey ON taska.issue_worklogs USING btree (id)`
- `CREATE INDEX issue_worklogs_project_id_work_date_active_idx ON taska.issue_worklogs USING btree (project_id, work_date) WHERE (deleted_at IS NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0008-add-issue-worklogs.sql](../../issue-service/src/main/resources/db/changelog/changes/0008-add-issue-worklogs.sql)
- [issue-service/src/main/resources/db/changelog/changes/0011-worklog-page-index.sql](../../issue-service/src/main/resources/db/changelog/changes/0011-worklog-page-index.sql)

### issue-service · taska.issues

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `project_id` `uuid` NOT NULL
- `issue_number` `integer` NOT NULL
- `issue_key` `text` NOT NULL
- `issue_type` `text` NOT NULL
- `summary` `text` NOT NULL
- `description` `text` NULL
- `status_key` `text` NOT NULL
- `priority` `text` NOT NULL
- `assignee_id` `uuid` NULL
- `reporter_id` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `version` `integer` NOT NULL DEFAULT `1`
- `deleted_at` `timestamp with time zone` NULL
- `story_points` `numeric(5,2)` NULL
- `start_date` `date` NULL
- `due_date` `date` NULL
- `original_estimate_minutes` `integer` NULL
- `remaining_estimate_minutes` `integer` NULL
- `time_spent_minutes` `integer` NOT NULL DEFAULT `0`

Ограничения:

- `issues_dates_chk`: `CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date)`
- `issues_issue_key_key`: `UNIQUE (issue_key)`
- `issues_original_estimate_chk`: `CHECK (original_estimate_minutes IS NULL OR original_estimate_minutes >= 0)`
- `issues_pkey`: `PRIMARY KEY (id)`
- `issues_priority_chk`: `CHECK (priority = ANY (ARRAY['LOW'::text, 'MEDIUM'::text, 'HIGH'::text]))`
- `issues_project_number_uniq`: `UNIQUE (project_id, issue_number)`
- `issues_remaining_estimate_chk`: `CHECK (remaining_estimate_minutes IS NULL OR remaining_estimate_minutes >= 0)`
- `issues_story_points_chk`: `CHECK (story_points IS NULL OR story_points >= 0::numeric)`
- `issues_time_spent_minutes_chk`: `CHECK (time_spent_minutes >= 0)`
- `issues_type_key_chk`: `CHECK (issue_type ~ '^[A-Z][A-Z0-9_]{1,31}$'::text)`
- `issues_version_chk`: `CHECK (version >= 1)`

Индексы:

- `CREATE INDEX idx_issues_assignee_id ON taska.issues USING btree (assignee_id) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_description_trgm ON taska.issues USING gin (description gin_trgm_ops) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_issue_key ON taska.issues USING btree (issue_key) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_issue_type ON taska.issues USING btree (issue_type) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_priority ON taska.issues USING btree (priority) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_project_id ON taska.issues USING btree (project_id) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_reporter_id ON taska.issues USING btree (reporter_id) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_search_composite ON taska.issues USING btree (project_id, status_key, assignee_id, reporter_id, priority, issue_type) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_status_key ON taska.issues USING btree (status_key) WHERE (deleted_at IS NULL)`
- `CREATE INDEX idx_issues_summary_trgm ON taska.issues USING gin (summary gin_trgm_ops) WHERE (deleted_at IS NULL)`
- `CREATE INDEX issues_board_column_cursor_active_idx ON taska.issues USING btree (project_id, issue_type, status_key, created_at, id) WHERE (deleted_at IS NULL)`
- `CREATE INDEX issues_id_active_idx ON taska.issues USING btree (id) WHERE (deleted_at IS NULL)`
- `CREATE UNIQUE INDEX issues_issue_key_key ON taska.issues USING btree (issue_key)`
- `CREATE UNIQUE INDEX issues_pkey ON taska.issues USING btree (id)`
- `CREATE INDEX issues_project_assignee_idx ON taska.issues USING btree (project_id, assignee_id)`
- `CREATE UNIQUE INDEX issues_project_number_uniq ON taska.issues USING btree (project_id, issue_number)`
- `CREATE INDEX issues_project_priority_idx ON taska.issues USING btree (project_id, priority)`
- `CREATE INDEX issues_project_status_idx ON taska.issues USING btree (project_id, status_key)`
- `CREATE INDEX issues_project_type_idx ON taska.issues USING btree (project_id, issue_type)`
- `CREATE INDEX issues_project_updated_at_idx ON taska.issues USING btree (project_id, updated_at)`
- `CREATE INDEX issues_status_key_idx ON taska.issues USING btree (status_key)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0000-init.sql](../../issue-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [issue-service/src/main/resources/db/changelog/changes/0001-issue-attachments.sql](../../issue-service/src/main/resources/db/changelog/changes/0001-issue-attachments.sql)
- [issue-service/src/main/resources/db/changelog/changes/0003-add-issue-comments.sql](../../issue-service/src/main/resources/db/changelog/changes/0003-add-issue-comments.sql)
- [issue-service/src/main/resources/db/changelog/changes/0005-add-issue-watchers.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-add-issue-watchers.sql)
- [issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql)
- [issue-service/src/main/resources/db/changelog/changes/0005-board-indexes.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-board-indexes.sql)
- [issue-service/src/main/resources/db/changelog/changes/0006_add_issue_search_indexes.sql](../../issue-service/src/main/resources/db/changelog/changes/0006_add_issue_search_indexes.sql)
- [issue-service/src/main/resources/db/changelog/changes/0007-issue-planing-fields.sql](../../issue-service/src/main/resources/db/changelog/changes/0007-issue-planing-fields.sql)
- [issue-service/src/main/resources/db/changelog/changes/0008-add-issue-worklogs.sql](../../issue-service/src/main/resources/db/changelog/changes/0008-add-issue-worklogs.sql)
- [issue-service/src/main/resources/db/changelog/changes/0010-open-issue-types.sql](../../issue-service/src/main/resources/db/changelog/changes/0010-open-issue-types.sql)
- [issue-service/src/main/resources/db/changelog/changes/0012-board-column-cursor.sql](../../issue-service/src/main/resources/db/changelog/changes/0012-board-column-cursor.sql)

### issue-service · taska.outbox_events

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `uuid` NOT NULL
- `event_type` `text` NOT NULL
- `payload` `jsonb` NOT NULL
- `status` `text` NOT NULL DEFAULT `'NEW'::text`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `published_at` `timestamp with time zone` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `last_error_message` `text` NULL
- `processing_started_at` `timestamp with time zone` NULL
- `request_id` `text` NULL

Ограничения:

- `outbox_events_attempts_chk`: `CHECK (attempts >= 0)`
- `outbox_events_pkey`: `PRIMARY KEY (id)`
- `outbox_events_status_chk`: `CHECK (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text, 'PUBLISHED'::text, 'FAILED'::text]))`

Индексы:

- `CREATE INDEX outbox_events_aggregate_idx ON taska.outbox_events USING btree (aggregate_type, aggregate_id)`
- `CREATE UNIQUE INDEX outbox_events_pkey ON taska.outbox_events USING btree (id)`
- `CREATE INDEX outbox_events_processing_idx ON taska.outbox_events USING btree (status, processing_started_at) WHERE ((status = 'PROCESSING'::text) AND (processing_started_at IS NOT NULL))`
- `CREATE INDEX outbox_events_status_created_idx ON taska.outbox_events USING btree (status, created_at) WHERE (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text]))`
- `CREATE INDEX outbox_events_unpublished_idx ON taska.outbox_events USING btree (created_at) WHERE (published_at IS NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0000-init.sql](../../issue-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [issue-service/src/main/resources/db/changelog/changes/0009-redact-comment-event-text.sql](../../issue-service/src/main/resources/db/changelog/changes/0009-redact-comment-event-text.sql)

### issue-service · taska.project_counters

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `project_id` `uuid` NOT NULL
- `next_issue_number` `integer` NOT NULL DEFAULT `1`

Ограничения:

- `project_counters_next_issue_number_chk`: `CHECK (next_issue_number >= 1)`
- `project_counters_pkey`: `PRIMARY KEY (project_id)`

Индексы:

- `CREATE UNIQUE INDEX project_counters_pkey ON taska.project_counters USING btree (project_id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0000-init.sql](../../issue-service/src/main/resources/db/changelog/changes/0000-init.sql)

### issue-service · taska.project_labels

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `project_id` `uuid` NOT NULL
- `name` `text` NOT NULL
- `color` `text` NOT NULL
- `created_by` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `deleted_at` `timestamp with time zone` NULL

Ограничения:

- `check_label_name_not_empty`: `CHECK (length(TRIM(BOTH FROM name)) > 0)`
- `project_labels_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE INDEX idx_project_labels_project_id ON taska.project_labels USING btree (project_id)`
- `CREATE UNIQUE INDEX project_labels_pkey ON taska.project_labels USING btree (id)`
- `CREATE UNIQUE INDEX unique_project_label_active_name_index ON taska.project_labels USING btree (project_id, lower(TRIM(BOTH FROM name))) WHERE (deleted_at IS NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql](../../issue-service/src/main/resources/db/changelog/changes/0005-add-labels-tables.sql)

### issue-service · taska.workflow_binding_receipts

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `operation_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `changed_count` `bigint` NOT NULL
- `scope` `jsonb` NOT NULL
- `released` `boolean` NOT NULL DEFAULT `false`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `workflow_binding_receipts_changed_count_check`: `CHECK (changed_count >= 0)`
- `workflow_binding_receipts_pkey`: `PRIMARY KEY (operation_id)`
- `workflow_binding_receipts_state_check`: `CHECK (state = ANY (ARRAY['APPLIED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE INDEX workflow_binding_active_fences ON taska.workflow_binding_receipts USING btree (project_id) WHERE ((state = 'APPLIED'::text) AND (NOT released))`
- `CREATE UNIQUE INDEX workflow_binding_receipts_pkey ON taska.workflow_binding_receipts USING btree (operation_id)`

Источники (создание, изменения и затрагивающие SQL):

- [issue-service/src/main/resources/db/changelog/changes/0014-workflow-binding-receipts.sql](../../issue-service/src/main/resources/db/changelog/changes/0014-workflow-binding-receipts.sql)

### notification-service · taska.email_delivery_attempts

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `notification_id` `uuid` NOT NULL
- `to_email` `text` NOT NULL
- `subject` `text` NOT NULL
- `status` `text` NOT NULL DEFAULT `'PENDING'::text`
- `attempts` `integer` NOT NULL DEFAULT `0`
- `last_error` `text` NULL
- `next_retry_at` `timestamp with time zone` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `email_delivery_attempts_notification_id_fkey`: `FOREIGN KEY (notification_id) REFERENCES taska.notifications(id) ON DELETE CASCADE`
- `email_delivery_attempts_pkey`: `PRIMARY KEY (id)`
- `email_delivery_attempts_status_chk`: `CHECK (status = ANY (ARRAY['PENDING'::text, 'SENT'::text, 'FAILED'::text]))`

Индексы:

- `CREATE INDEX email_attempts_notification_idx ON taska.email_delivery_attempts USING btree (notification_id)`
- `CREATE INDEX email_attempts_status_next_retry_idx ON taska.email_delivery_attempts USING btree (status, next_retry_at)`
- `CREATE UNIQUE INDEX email_delivery_attempts_pkey ON taska.email_delivery_attempts USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0000-init.sql](../../notification-service/src/main/resources/db/changelog/changes/0000-init.sql)

### notification-service · taska.invitation_email_deliveries

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `user_id` `uuid` NOT NULL
- `event_id` `uuid` NOT NULL
- `email` `text` NOT NULL
- `token` `text` NULL
- `issued_at` `timestamp with time zone` NOT NULL
- `expires_at` `timestamp with time zone` NOT NULL
- `status` `text` NOT NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `next_attempt_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `last_attempt_at` `timestamp with time zone` NULL
- `lease_id` `uuid` NULL
- `message_kind` `text` NOT NULL DEFAULT `'INVITATION'::text`

Ограничения:

- `invitation_email_deliveries_event_id_key`: `UNIQUE (event_id)`
- `invitation_email_deliveries_message_kind_check`: `CHECK (message_kind = ANY (ARRAY['INVITATION'::text, 'SIGNUP'::text, 'EXISTING_ACCOUNT'::text]))`
- `invitation_email_deliveries_pkey`: `PRIMARY KEY (user_id)`
- `invitation_email_status_chk`: `CHECK (status = ANY (ARRAY['PENDING'::text, 'PROCESSING'::text, 'RETRY'::text, 'SENT'::text, 'EXPIRED'::text, 'FAILED'::text]))`

Индексы:

- `CREATE UNIQUE INDEX invitation_email_deliveries_event_id_key ON taska.invitation_email_deliveries USING btree (event_id)`
- `CREATE UNIQUE INDEX invitation_email_deliveries_pkey ON taska.invitation_email_deliveries USING btree (user_id)`
- `CREATE INDEX invitation_email_due_idx ON taska.invitation_email_deliveries USING btree (status, next_attempt_at)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0003-invitation-email-queue.sql](../../notification-service/src/main/resources/db/changelog/changes/0003-invitation-email-queue.sql)
- [notification-service/src/main/resources/db/changelog/changes/0006-signup-email-kind.sql](../../notification-service/src/main/resources/db/changelog/changes/0006-signup-email-kind.sql)

### notification-service · taska.notification_channel_throttle

Класс: `GLOBAL_ACCOUNT`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `channel` `text` NOT NULL
- `next_send_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `notification_channel_throttle_pkey`: `PRIMARY KEY (channel)`

Индексы:

- `CREATE UNIQUE INDEX notification_channel_throttle_pkey ON taska.notification_channel_throttle USING btree (channel)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql](../../notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql)

### notification-service · taska.notification_deliveries

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `dedup_key` `text` NOT NULL
- `notification_id` `uuid` NULL
- `user_id` `uuid` NULL
- `channel` `text` NOT NULL
- `channel_version` `uuid` NULL
- `reply_chat_id` `bigint` NULL
- `reply_text` `text` NULL
- `bot_username` `text` NULL
- `status` `text` NOT NULL DEFAULT `'PENDING'::text`
- `attempts` `integer` NOT NULL DEFAULT `0`
- `next_attempt_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `last_attempt_at` `timestamp with time zone` NULL
- `lease_id` `uuid` NULL
- `last_error` `text` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `notification_deliveries_channel_check`: `CHECK (channel = ANY (ARRAY['EMAIL'::text, 'TELEGRAM'::text]))`
- `notification_deliveries_check`: `CHECK (notification_id IS NOT NULL AND user_id IS NOT NULL AND channel_version IS NOT NULL AND reply_chat_id IS NULL AND reply_text IS NULL OR notification_id IS NULL AND channel = 'TELEGRAM'::text AND reply_chat_id IS NOT NULL AND reply_text IS NOT NULL)`
- `notification_deliveries_dedup_key_key`: `UNIQUE (dedup_key)`
- `notification_deliveries_notification_id_channel_key`: `UNIQUE (notification_id, channel)`
- `notification_deliveries_notification_id_fkey`: `FOREIGN KEY (notification_id) REFERENCES taska.notifications(id) ON DELETE CASCADE`
- `notification_deliveries_pkey`: `PRIMARY KEY (id)`
- `notification_deliveries_status_check`: `CHECK (status = ANY (ARRAY['PENDING'::text, 'PROCESSING'::text, 'RETRY'::text, 'SENT'::text, 'FAILED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE UNIQUE INDEX notification_deliveries_dedup_key_key ON taska.notification_deliveries USING btree (dedup_key)`
- `CREATE INDEX notification_deliveries_due_idx ON taska.notification_deliveries USING btree (channel, status, next_attempt_at)`
- `CREATE UNIQUE INDEX notification_deliveries_notification_id_channel_key ON taska.notification_deliveries USING btree (notification_id, channel)`
- `CREATE UNIQUE INDEX notification_deliveries_pkey ON taska.notification_deliveries USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql](../../notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql)

### notification-service · taska.notification_preferences

Класс: `GLOBAL_ACCOUNT`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `user_id` `uuid` NOT NULL
- `email` `text` NULL
- `in_app_enabled` `boolean` NOT NULL DEFAULT `true`
- `email_enabled` `boolean` NOT NULL DEFAULT `true`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `email_version` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `telegram_enabled` `boolean` NOT NULL DEFAULT `false`
- `telegram_chat_id` `bigint` NULL
- `telegram_user_id` `bigint` NULL
- `telegram_username` `text` NULL
- `telegram_name` `text` NULL
- `telegram_bot_username` `text` NULL
- `telegram_version` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `telegram_error` `text` NULL

Ограничения:

- `notification_preferences_pkey`: `PRIMARY KEY (user_id)`
- `notification_preferences_telegram_chat_id_key`: `UNIQUE (telegram_chat_id)`
- `notification_preferences_telegram_user_id_key`: `UNIQUE (telegram_user_id)`

Индексы:

- `CREATE UNIQUE INDEX notification_preferences_pkey ON taska.notification_preferences USING btree (user_id)`
- `CREATE UNIQUE INDEX notification_preferences_telegram_chat_id_key ON taska.notification_preferences USING btree (telegram_chat_id)`
- `CREATE UNIQUE INDEX notification_preferences_telegram_user_id_key ON taska.notification_preferences USING btree (telegram_user_id)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0000-init.sql](../../notification-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql](../../notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql)

### notification-service · taska.notifications

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `user_id` `uuid` NOT NULL
- `notification_type` `text` NOT NULL
- `title` `text` NOT NULL
- `body` `text` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `read_at` `timestamp with time zone` NULL
- `source_event_id` `uuid` NOT NULL
- `issue_id` `uuid` NULL
- `issue_key` `text` NULL
- `project_id` `uuid` NULL

Ограничения:

- `notifications_pkey`: `PRIMARY KEY (id)`
- `notifications_type_chk`: `CHECK (notification_type = ANY (ARRAY['ISSUE_ASSIGNED'::text, 'ISSUE_TRANSITIONED'::text, 'ISSUE_CREATED'::text, 'ISSUE_UPDATED'::text, 'ISSUE_DELETED'::text, 'ISSUE_LINK_CREATED'::text, 'ISSUE_LINK_DELETED'::text, 'USER_INVITED'::text, 'USER_ACTIVATED'::text, 'MEMBER_REMOVED'::text, 'MEMBER_ADDED'::text, 'MEMBER_UPDATED'::text, 'PROJECT_CREATED'::text, 'PROJECT_ARCHIVED'::text, 'PERSONAL_ACCESS_TOKEN_CREATED'::text, 'PERSONAL_ACCESS_TOKEN_REVOKED'::text, 'LABEL_ADDED'::text, 'LABEL_REMOVED'::text, 'USER_BLOCKED'::text, 'USER_UNBLOCKED'::text, 'ISSUE_COMMENT_CREATED'::text, 'ISSUE_COMMENT_UPDATED'::text, 'ISSUE_COMMENT_DELETED'::text, 'ISSUE_ATTACHMENT_ADDED'::text, 'ISSUE_ATTACHMENT_DELETED'::text]))`

Индексы:

- `CREATE INDEX notifications_issue_id_idx ON taska.notifications USING btree (issue_id)`
- `CREATE UNIQUE INDEX notifications_pkey ON taska.notifications USING btree (id)`
- `CREATE UNIQUE INDEX notifications_source_event_user_uniq ON taska.notifications USING btree (source_event_id, user_id)`
- `CREATE INDEX notifications_user_created_idx ON taska.notifications USING btree (user_id, created_at)`
- `CREATE INDEX notifications_user_unread_idx ON taska.notifications USING btree (user_id) WHERE (read_at IS NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0000-init.sql](../../notification-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [notification-service/src/main/resources/db/changelog/changes/0001-modify-notifications-type-check.sql](../../notification-service/src/main/resources/db/changelog/changes/0001-modify-notifications-type-check.sql)
- [notification-service/src/main/resources/db/changelog/changes/0002-add-label-notification-types.sql](../../notification-service/src/main/resources/db/changelog/changes/0002-add-label-notification-types.sql)
- [notification-service/src/main/resources/db/changelog/changes/0003-add-user-blocked-unblocked-notification-types.sql](../../notification-service/src/main/resources/db/changelog/changes/0003-add-user-blocked-unblocked-notification-types.sql)
- [notification-service/src/main/resources/db/changelog/changes/0004-add-comment-notification-types.sql](../../notification-service/src/main/resources/db/changelog/changes/0004-add-comment-notification-types.sql)
- [notification-service/src/main/resources/db/changelog/changes/0005-add-attachment-notification-types.sql](../../notification-service/src/main/resources/db/changelog/changes/0005-add-attachment-notification-types.sql)
- [notification-service/src/main/resources/db/changelog/changes/0006-add-issue-fields-to-notifications.sql](../../notification-service/src/main/resources/db/changelog/changes/0006-add-issue-fields-to-notifications.sql)
- [notification-service/src/main/resources/db/changelog/changes/0007-optimize-unread-notifications-index.sql](../../notification-service/src/main/resources/db/changelog/changes/0007-optimize-unread-notifications-index.sql)
- [notification-service/src/main/resources/db/changelog/changes/0008-redact-comment-notification-previews.sql](../../notification-service/src/main/resources/db/changelog/changes/0008-redact-comment-notification-previews.sql)
- [notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql](../../notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql)
- [notification-service/src/main/resources/db/changelog/changes/0010-project-archived-notification.sql](../../notification-service/src/main/resources/db/changelog/changes/0010-project-archived-notification.sql)
- [notification-service/src/main/resources/db/changelog/changes/0011-personal-token-notifications.sql](../../notification-service/src/main/resources/db/changelog/changes/0011-personal-token-notifications.sql)

### notification-service · taska.processed_events

Класс: `MIXED_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `event_id` `uuid` NOT NULL
- `processed_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `source_type` `text` NOT NULL

Ограничения:

- `processed_events_event_id_key`: `UNIQUE (event_id)`
- `processed_events_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE UNIQUE INDEX processed_events_event_id_key ON taska.processed_events USING btree (event_id)`
- `CREATE UNIQUE INDEX processed_events_pkey ON taska.processed_events USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0000-init.sql](../../notification-service/src/main/resources/db/changelog/changes/0000-init.sql)

### notification-service · taska.telegram_link_requests

Класс: `GLOBAL_ACCOUNT`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `user_id` `uuid` NOT NULL
- `link_id` `uuid` NOT NULL
- `token_hash` `text` NOT NULL
- `bot_username` `text` NOT NULL
- `expires_at` `timestamp with time zone` NOT NULL
- `consumed_at` `timestamp with time zone` NULL
- `candidate_chat_id` `bigint` NULL
- `candidate_user_id` `bigint` NULL
- `candidate_username` `text` NULL
- `candidate_name` `text` NULL

Ограничения:

- `telegram_link_requests_link_id_key`: `UNIQUE (link_id)`
- `telegram_link_requests_pkey`: `PRIMARY KEY (user_id)`
- `telegram_link_requests_token_hash_key`: `UNIQUE (token_hash)`
- `telegram_link_requests_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES taska.notification_preferences(user_id)`

Индексы:

- `CREATE UNIQUE INDEX telegram_link_requests_link_id_key ON taska.telegram_link_requests USING btree (link_id)`
- `CREATE UNIQUE INDEX telegram_link_requests_pkey ON taska.telegram_link_requests USING btree (user_id)`
- `CREATE UNIQUE INDEX telegram_link_requests_token_hash_key ON taska.telegram_link_requests USING btree (token_hash)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql](../../notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql)

### notification-service · taska.telegram_updates

Класс: `GLOBAL_ACCOUNT`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `bot_username` `text` NOT NULL
- `update_id` `bigint` NOT NULL
- `processed_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `telegram_updates_pkey`: `PRIMARY KEY (bot_username, update_id)`

Индексы:

- `CREATE UNIQUE INDEX telegram_updates_pkey ON taska.telegram_updates USING btree (bot_username, update_id)`

Источники (создание, изменения и затрагивающие SQL):

- [notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql](../../notification-service/src/main/resources/db/changelog/changes/0009-notification-channels.sql)

### oauth-server · public.oauth2_authorization

Класс: `GRANT_SCOPED`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `character varying(100)` NOT NULL
- `registered_client_id` `character varying(2048)` NOT NULL
- `principal_name` `character varying(200)` NOT NULL
- `authorization_grant_type` `character varying(100)` NOT NULL
- `authorized_scopes` `character varying(1000)` NULL DEFAULT `NULL::character varying`
- `attributes` `text` NULL
- `state` `character varying(500)` NULL DEFAULT `NULL::character varying`
- `authorization_code_value` `text` NULL
- `authorization_code_issued_at` `timestamp with time zone` NULL
- `authorization_code_expires_at` `timestamp with time zone` NULL
- `authorization_code_metadata` `text` NULL
- `access_token_value` `text` NULL
- `access_token_issued_at` `timestamp with time zone` NULL
- `access_token_expires_at` `timestamp with time zone` NULL
- `access_token_metadata` `text` NULL
- `access_token_type` `character varying(100)` NULL DEFAULT `NULL::character varying`
- `access_token_scopes` `character varying(1000)` NULL DEFAULT `NULL::character varying`
- `oidc_id_token_value` `text` NULL
- `oidc_id_token_issued_at` `timestamp with time zone` NULL
- `oidc_id_token_expires_at` `timestamp with time zone` NULL
- `oidc_id_token_metadata` `text` NULL
- `refresh_token_value` `text` NULL
- `refresh_token_issued_at` `timestamp with time zone` NULL
- `refresh_token_expires_at` `timestamp with time zone` NULL
- `refresh_token_metadata` `text` NULL
- `user_code_value` `text` NULL
- `user_code_issued_at` `timestamp with time zone` NULL
- `user_code_expires_at` `timestamp with time zone` NULL
- `user_code_metadata` `text` NULL
- `device_code_value` `text` NULL
- `device_code_issued_at` `timestamp with time zone` NULL
- `device_code_expires_at` `timestamp with time zone` NULL
- `device_code_metadata` `text` NULL

Ограничения:

- `oauth2_authorization_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE UNIQUE INDEX oauth2_authorization_pkey ON public.oauth2_authorization USING btree (id)`
- `CREATE INDEX oauth_access_token_idx ON public.oauth2_authorization USING btree (access_token_value) WHERE (access_token_value IS NOT NULL)`
- `CREATE INDEX oauth_authorization_code_idx ON public.oauth2_authorization USING btree (authorization_code_value) WHERE (authorization_code_value IS NOT NULL)`
- `CREATE INDEX oauth_consent_state_idx ON public.oauth2_authorization USING btree (state) WHERE (state IS NOT NULL)`
- `CREATE INDEX oauth_grant_owner_idx ON public.oauth2_authorization USING btree (principal_name)`
- `CREATE INDEX oauth_refresh_token_idx ON public.oauth2_authorization USING btree (refresh_token_value) WHERE (refresh_token_value IS NOT NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [oauth-server/src/main/resources/db/changelog/0001-oauth.sql](../../oauth-server/src/main/resources/db/changelog/0001-oauth.sql)

### oauth-server · public.oauth2_authorization_consent

Класс: `GRANT_SCOPED`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `registered_client_id` `character varying(2048)` NOT NULL
- `principal_name` `character varying(200)` NOT NULL
- `authorities` `character varying(1000)` NOT NULL

Ограничения:

- `oauth2_authorization_consent_pkey`: `PRIMARY KEY (registered_client_id, principal_name)`

Индексы:

- `CREATE UNIQUE INDEX oauth2_authorization_consent_pkey ON public.oauth2_authorization_consent USING btree (registered_client_id, principal_name)`

Источники (создание, изменения и затрагивающие SQL):

- [oauth-server/src/main/resources/db/changelog/0001-oauth.sql](../../oauth-server/src/main/resources/db/changelog/0001-oauth.sql)

### oauth-server · public.oauth2_registered_client

Класс: `GLOBAL_INTEGRATION`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `character varying(100)` NOT NULL
- `client_id` `character varying(2048)` NOT NULL
- `client_id_issued_at` `timestamp with time zone` NOT NULL DEFAULT `CURRENT_TIMESTAMP`
- `client_secret` `character varying(200)` NULL DEFAULT `NULL::character varying`
- `client_secret_expires_at` `timestamp with time zone` NULL
- `client_name` `character varying(200)` NOT NULL
- `client_authentication_methods` `character varying(1000)` NOT NULL
- `authorization_grant_types` `character varying(1000)` NOT NULL
- `redirect_uris` `text` NULL
- `post_logout_redirect_uris` `text` NULL
- `scopes` `character varying(1000)` NOT NULL
- `client_settings` `text` NOT NULL
- `token_settings` `text` NOT NULL

Ограничения:

- `oauth2_registered_client_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE UNIQUE INDEX oauth2_registered_client_pkey ON public.oauth2_registered_client USING btree (id)`
- `CREATE UNIQUE INDEX oauth_client_id_unique ON public.oauth2_registered_client USING btree (client_id)`

Источники (создание, изменения и затрагивающие SQL):

- [oauth-server/src/main/resources/db/changelog/0001-oauth.sql](../../oauth-server/src/main/resources/db/changelog/0001-oauth.sql)

### oauth-server · public.oauth_refresh_history

Класс: `GRANT_SCOPED`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `token_hash` `character varying(64)` NOT NULL
- `grant_id` `character varying(100)` NOT NULL
- `used_at` `timestamp with time zone` NULL
- `family_expires_at` `timestamp with time zone` NOT NULL

Ограничения:

- `oauth_refresh_history_grant_id_fkey`: `FOREIGN KEY (grant_id) REFERENCES oauth2_authorization(id) ON DELETE CASCADE`
- `oauth_refresh_history_pkey`: `PRIMARY KEY (token_hash)`

Индексы:

- `CREATE INDEX oauth_refresh_history_grant_idx ON public.oauth_refresh_history USING btree (grant_id)`
- `CREATE UNIQUE INDEX oauth_refresh_history_pkey ON public.oauth_refresh_history USING btree (token_hash)`

Источники (создание, изменения и затрагивающие SQL):

- [oauth-server/src/main/resources/db/changelog/0001-oauth.sql](../../oauth-server/src/main/resources/db/changelog/0001-oauth.sql)

### oauth-server · public.oauth_registration_lock

Класс: `SYSTEM`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `integer` NOT NULL

Ограничения:

- `oauth_registration_lock_pkey`: `PRIMARY KEY (id)`

Индексы:

- `CREATE UNIQUE INDEX oauth_registration_lock_pkey ON public.oauth_registration_lock USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [oauth-server/src/main/resources/db/changelog/0001-oauth.sql](../../oauth-server/src/main/resources/db/changelog/0001-oauth.sql)

### project-service · taska.issue_type_creations

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `actor_user_id` `uuid` NOT NULL
- `request_id` `text` NOT NULL
- `node_id` `text` NOT NULL
- `command_hash` `text` NOT NULL
- `code` `text` NOT NULL
- `generation` `uuid` NOT NULL
- `name` `text` NOT NULL
- `color` `text` NOT NULL
- `icon` `text` NULL
- `shape` `text` NOT NULL
- `sort_order` `integer` NOT NULL
- `desired_workflow_id` `uuid` NULL
- `first_type_code` `text` NULL
- `first_type_generation` `uuid` NULL
- `resolved_workflow_id` `uuid` NULL
- `channel` `text` NULL
- `token_id` `uuid` NULL
- `state` `text` NOT NULL
- `error_status` `text` NULL
- `error_message` `text` NULL
- `error_details` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `creation_default_snapshot_required`: `CHECK (first_type_code IS NOT NULL AND first_type_generation IS NOT NULL OR first_type_code IS NULL AND first_type_generation IS NULL AND desired_workflow_id IS NOT NULL)`
- `issue_type_creations_id_fkey`: `FOREIGN KEY (id) REFERENCES taska.project_type_operations(id)`
- `issue_type_creations_pkey`: `PRIMARY KEY (id)`
- `issue_type_creations_project_id_actor_user_id_request_id_key`: `UNIQUE (project_id, actor_user_id, request_id)`
- `issue_type_creations_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES taska.projects(id)`
- `issue_type_creations_state_check`: `CHECK (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text, 'COMPLETED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE INDEX issue_type_creation_recovery ON taska.issue_type_creations USING btree (updated_at) WHERE (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text]))`
- `CREATE UNIQUE INDEX issue_type_creations_pkey ON taska.issue_type_creations USING btree (id)`
- `CREATE UNIQUE INDEX issue_type_creations_project_id_actor_user_id_request_id_key ON taska.issue_type_creations USING btree (project_id, actor_user_id, request_id)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql](../../project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql)
- [project-service/src/main/resources/db/changelog/changes/0006-first-type-explicit-workflow.sql](../../project-service/src/main/resources/db/changelog/changes/0006-first-type-explicit-workflow.sql)

### project-service · taska.issue_type_deletions

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `source_code` `text` NOT NULL
- `source_generation` `uuid` NOT NULL
- `target_code` `text` NULL
- `target_generation` `uuid` NULL
- `actor_user_id` `uuid` NOT NULL
- `status_mappings` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `state` `text` NOT NULL
- `request_id` `text` NOT NULL
- `node_id` `text` NOT NULL
- `channel` `text` NULL
- `token_id` `uuid` NULL
- `reservation_released` `boolean` NOT NULL DEFAULT `false`
- `error_status` `text` NULL
- `error_message` `text` NULL
- `error_details` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `issue_type_deletions_check`: `CHECK ((target_code IS NULL) = (target_generation IS NULL))`
- `issue_type_deletions_pkey`: `PRIMARY KEY (id)`
- `issue_type_deletions_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES taska.projects(id)`
- `issue_type_deletions_state_check`: `CHECK (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text, 'COMPLETED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE INDEX issue_type_deletion_lifecycle_idx ON taska.issue_type_deletions USING btree (project_id, source_code, source_generation)`
- `CREATE INDEX issue_type_deletion_recovery_idx ON taska.issue_type_deletions USING btree (updated_at) WHERE (reservation_released = false)`
- `CREATE UNIQUE INDEX issue_type_deletions_pkey ON taska.issue_type_deletions USING btree (id)`
- `CREATE UNIQUE INDEX issue_type_one_pending_deletion_per_project ON taska.issue_type_deletions USING btree (project_id) WHERE (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text]))`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0004-issue-type-deletion-intents.sql](../../project-service/src/main/resources/db/changelog/changes/0004-issue-type-deletion-intents.sql)
- [project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql](../../project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql)

### project-service · taska.outbox_events

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `aggregate_type` `text` NOT NULL
- `aggregate_id` `uuid` NOT NULL
- `event_type` `text` NOT NULL
- `payload` `jsonb` NOT NULL
- `status` `text` NOT NULL DEFAULT `'NEW'::text`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `published_at` `timestamp with time zone` NULL
- `attempts` `integer` NOT NULL DEFAULT `0`
- `last_error_message` `text` NULL
- `processing_started_at` `timestamp with time zone` NULL
- `request_id` `text` NULL

Ограничения:

- `outbox_events_attempts_chk`: `CHECK (attempts >= 0)`
- `outbox_events_pkey`: `PRIMARY KEY (id)`
- `outbox_events_status_chk`: `CHECK (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text, 'PUBLISHED'::text, 'FAILED'::text]))`

Индексы:

- `CREATE INDEX outbox_events_aggregate_idx ON taska.outbox_events USING btree (aggregate_type, aggregate_id)`
- `CREATE UNIQUE INDEX outbox_events_pkey ON taska.outbox_events USING btree (id)`
- `CREATE INDEX outbox_events_processing_idx ON taska.outbox_events USING btree (status, processing_started_at) WHERE ((status = 'PROCESSING'::text) AND (processing_started_at IS NOT NULL))`
- `CREATE INDEX outbox_events_status_created_idx ON taska.outbox_events USING btree (status, created_at) WHERE (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text]))`
- `CREATE INDEX outbox_events_unpublished_idx ON taska.outbox_events USING btree (created_at) WHERE (published_at IS NULL)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0000-init.sql](../../project-service/src/main/resources/db/changelog/changes/0000-init.sql)

### project-service · taska.project_issue_types

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `project_id` `uuid` NOT NULL
- `code` `character varying(32)` NOT NULL
- `name` `character varying(50)` NOT NULL
- `color` `character varying(16)` NOT NULL
- `icon` `character varying(32)` NULL
- `sort_order` `integer` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `generation` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `shape` `character varying(16)` NOT NULL DEFAULT `'square'::character varying`
- `pending_operation_id` `uuid` NULL

Ограничения:

- `project_issue_types_code_check`: `CHECK (code::text ~ '^[A-Z][A-Z0-9_]{1,31}$'::text)`
- `project_issue_types_color_check`: `CHECK (color::text = ANY (ARRAY['blue'::character varying, 'red'::character varying, 'green'::character varying, 'amber'::character varying, 'purple'::character varying, 'teal'::character varying, 'slate'::character varying]::text[]))`
- `project_issue_types_icon_check`: `CHECK (icon::text = ANY (ARRAY['check-square'::character varying, 'bug'::character varying, 'book-open'::character varying, 'headset'::character varying, 'megaphone'::character varying, 'file-text'::character varying, 'flag'::character varying, 'layers'::character varying]::text[]))`
- `project_issue_types_name_check`: `CHECK (length(btrim(name::text)) > 0)`
- `project_issue_types_pending_operation_id_fkey`: `FOREIGN KEY (pending_operation_id) REFERENCES taska.project_type_operations(id)`
- `project_issue_types_pkey`: `PRIMARY KEY (project_id, code)`
- `project_issue_types_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES taska.projects(id) ON DELETE CASCADE`
- `project_issue_types_shape_check`: `CHECK (shape::text = ANY (ARRAY['square'::character varying, 'circle'::character varying, 'diamond'::character varying]::text[]))`
- `project_issue_types_sort_order_check`: `CHECK (sort_order >= 0)`

Индексы:

- `CREATE INDEX project_issue_types_order_idx ON taska.project_issue_types USING btree (project_id, sort_order, code)`
- `CREATE UNIQUE INDEX project_issue_types_pkey ON taska.project_issue_types USING btree (project_id, code)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0001-project-issue-types.sql](../../project-service/src/main/resources/db/changelog/changes/0001-project-issue-types.sql)
- [project-service/src/main/resources/db/changelog/changes/0002-issue-type-generation.sql](../../project-service/src/main/resources/db/changelog/changes/0002-issue-type-generation.sql)
- [project-service/src/main/resources/db/changelog/changes/0003-issue-type-shape.sql](../../project-service/src/main/resources/db/changelog/changes/0003-issue-type-shape.sql)
- [project-service/src/main/resources/db/changelog/changes/0004-issue-type-deletion-intents.sql](../../project-service/src/main/resources/db/changelog/changes/0004-issue-type-deletion-intents.sql)
- [project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql](../../project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql)

### project-service · taska.project_members

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `project_id` `uuid` NOT NULL
- `user_id` `uuid` NOT NULL
- `role` `text` NOT NULL
- `added_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `added_by` `uuid` NOT NULL

Ограничения:

- `ck_project_members_role`: `CHECK (role = ANY (ARRAY['ADMIN'::text, 'MEMBER'::text, 'VIEWER'::text]))`
- `project_members_pkey`: `PRIMARY KEY (project_id, user_id)`
- `project_members_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES taska.projects(id) ON DELETE CASCADE`

Индексы:

- `CREATE UNIQUE INDEX project_members_pkey ON taska.project_members USING btree (project_id, user_id)`
- `CREATE INDEX project_members_project_role_idx ON taska.project_members USING btree (project_id, role)`
- `CREATE INDEX project_members_user_idx ON taska.project_members USING btree (user_id)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0000-init.sql](../../project-service/src/main/resources/db/changelog/changes/0000-init.sql)

### project-service · taska.project_settings

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `project_id` `uuid` NOT NULL
- `settings` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_by` `uuid` NOT NULL

Ограничения:

- `project_settings_pkey`: `PRIMARY KEY (project_id)`
- `project_settings_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES taska.projects(id) ON DELETE CASCADE`

Индексы:

- `CREATE UNIQUE INDEX project_settings_pkey ON taska.project_settings USING btree (project_id)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0000-init.sql](../../project-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [project-service/src/main/resources/db/changelog/changes/0001-project-issue-types.sql](../../project-service/src/main/resources/db/changelog/changes/0001-project-issue-types.sql)

### project-service · taska.project_type_operations

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `kind` `text` NOT NULL
- `active` `boolean` NOT NULL DEFAULT `true`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `project_type_operations_kind_check`: `CHECK (kind = ANY (ARRAY['DELETE_TYPE'::text, 'WORKFLOW_BINDINGS'::text, 'CREATE_TYPE'::text]))`
- `project_type_operations_pkey`: `PRIMARY KEY (id)`
- `project_type_operations_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES taska.projects(id)`

Индексы:

- `CREATE UNIQUE INDEX project_type_one_active_operation ON taska.project_type_operations USING btree (project_id) WHERE active`
- `CREATE UNIQUE INDEX project_type_operations_pkey ON taska.project_type_operations USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql](../../project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql)

### project-service · taska.projects

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `project_key` `text` NOT NULL
- `name` `text` NOT NULL
- `created_by` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `archived_at` `timestamp with time zone` NULL
- `description` `text` NULL
- `color` `character varying(7)` NULL
- `version` `integer` NOT NULL DEFAULT `0`

Ограничения:

- `projects_pkey`: `PRIMARY KEY (id)`
- `projects_project_key_key`: `UNIQUE (project_key)`

Индексы:

- `CREATE INDEX projects_created_by_idx ON taska.projects USING btree (created_by)`
- `CREATE UNIQUE INDEX projects_pkey ON taska.projects USING btree (id)`
- `CREATE UNIQUE INDEX projects_project_key_key ON taska.projects USING btree (project_key)`

Источники (создание, изменения и затрагивающие SQL):

- [project-service/src/main/resources/db/changelog/changes/0000-init.sql](../../project-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [project-service/src/main/resources/db/changelog/changes/0001-add-description-and-color-for-projects.sql](../../project-service/src/main/resources/db/changelog/changes/0001-add-description-and-color-for-projects.sql)
- [project-service/src/main/resources/db/changelog/changes/0001-project-issue-types.sql](../../project-service/src/main/resources/db/changelog/changes/0001-project-issue-types.sql)
- [project-service/src/main/resources/db/changelog/changes/0002-add-version-to-projects.sql](../../project-service/src/main/resources/db/changelog/changes/0002-add-version-to-projects.sql)
- [project-service/src/main/resources/db/changelog/changes/0004-issue-type-deletion-intents.sql](../../project-service/src/main/resources/db/changelog/changes/0004-issue-type-deletion-intents.sql)
- [project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql](../../project-service/src/main/resources/db/changelog/changes/0005-project-type-operations.sql)

### workflow-service · taska.issue_type_creation_workflow_receipts

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `operation_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `state` `text` NOT NULL
- `workflow_id` `uuid` NULL
- `code` `text` NULL
- `generation` `uuid` NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `issue_type_creation_workflow_receipts_pkey`: `PRIMARY KEY (operation_id)`
- `issue_type_creation_workflow_receipts_state_check`: `CHECK (state = ANY (ARRAY['APPLIED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE UNIQUE INDEX issue_type_creation_workflow_receipts_pkey ON taska.issue_type_creation_workflow_receipts USING btree (operation_id)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql](../../workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql)

### workflow-service · taska.issue_type_move_workflow_pins

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `operation_id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `workflow_id` `uuid` NOT NULL
- `valid_status_keys` `text[]` NOT NULL
- `released` `boolean` NOT NULL DEFAULT `false`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `issue_type_move_workflow_pins_pkey`: `PRIMARY KEY (operation_id)`

Индексы:

- `CREATE UNIQUE INDEX issue_type_move_workflow_pins_pkey ON taska.issue_type_move_workflow_pins USING btree (operation_id)`
- `CREATE UNIQUE INDEX issue_type_one_workflow_pin_per_project ON taska.issue_type_move_workflow_pins USING btree (project_id) WHERE (released = false)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0010-issue-type-move-pins.sql](../../workflow-service/src/main/resources/db/changelog/changes/0010-issue-type-move-pins.sql)

### workflow-service · taska.statuses

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `workflow_id` `uuid` NOT NULL
- `status_key` `text` NOT NULL
- `name` `text` NOT NULL
- `category` `text` NOT NULL
- `sort_order` `integer` NOT NULL DEFAULT `0`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `statuses_pkey`: `PRIMARY KEY (id)`
- `statuses_sort_positive_chk`: `CHECK (sort_order >= 0)`
- `statuses_workflow_id_fkey`: `FOREIGN KEY (workflow_id) REFERENCES taska.workflows(id) ON DELETE CASCADE`
- `statuses_workflow_status_uniq`: `UNIQUE (workflow_id, status_key)`

Индексы:

- `CREATE UNIQUE INDEX statuses_pkey ON taska.statuses USING btree (id)`
- `CREATE INDEX statuses_workflow_idx ON taska.statuses USING btree (workflow_id)`
- `CREATE UNIQUE INDEX statuses_workflow_status_uniq ON taska.statuses USING btree (workflow_id, status_key)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0000-init.sql](../../workflow-service/src/main/resources/db/changelog/changes/0000-init.sql)

### workflow-service · taska.transitions

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `workflow_id` `uuid` NOT NULL
- `from_status_id` `uuid` NOT NULL
- `to_status_id` `uuid` NOT NULL
- `name` `text` NOT NULL
- `is_hidden` `boolean` NOT NULL DEFAULT `false`
- `sort_order` `integer` NOT NULL DEFAULT `0`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `transitions_from_status_id_fkey`: `FOREIGN KEY (from_status_id) REFERENCES taska.statuses(id) ON DELETE CASCADE`
- `transitions_from_to_status_chk`: `CHECK (from_status_id <> to_status_id)`
- `transitions_pkey`: `PRIMARY KEY (id)`
- `transitions_sort_positive_chk`: `CHECK (sort_order >= 0)`
- `transitions_to_status_id_fkey`: `FOREIGN KEY (to_status_id) REFERENCES taska.statuses(id) ON DELETE CASCADE`
- `transitions_workflow_id_fkey`: `FOREIGN KEY (workflow_id) REFERENCES taska.workflows(id) ON DELETE CASCADE`

Индексы:

- `CREATE UNIQUE INDEX transitions_pkey ON taska.transitions USING btree (id)`
- `CREATE INDEX transitions_workflow_from_idx ON taska.transitions USING btree (workflow_id, from_status_id)`
- `CREATE UNIQUE INDEX transitions_workflow_from_to_uniq ON taska.transitions USING btree (workflow_id, from_status_id, to_status_id)`
- `CREATE INDEX transitions_workflow_to_idx ON taska.transitions USING btree (workflow_id, to_status_id)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0000-init.sql](../../workflow-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [workflow-service/src/main/resources/db/changelog/changes/0001-default-workflow-missing-transitions.sql](../../workflow-service/src/main/resources/db/changelog/changes/0001-default-workflow-missing-transitions.sql)

### workflow-service · taska.validator_rules

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `transition_id` `uuid` NOT NULL
- `rule_type` `text` NOT NULL
- `config` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `error_code` `text` NULL
- `error_message` `text` NULL
- `sort_order` `integer` NOT NULL DEFAULT `0`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `validator_rules_pkey`: `PRIMARY KEY (id)`
- `validator_rules_rule_type_chk`: `CHECK (rule_type = ANY (ARRAY['REQUIRE_FIELD'::text, 'REQUIRE_ASSIGNEE'::text, 'REQUIRE_COMMENT'::text]))`
- `validator_rules_sort_positive_chk`: `CHECK (sort_order >= 0)`
- `validator_rules_transition_id_fkey`: `FOREIGN KEY (transition_id) REFERENCES taska.transitions(id) ON DELETE CASCADE`
- `validator_rules_transition_rule_type_sort_uniq`: `UNIQUE (transition_id, rule_type, sort_order)`

Индексы:

- `CREATE UNIQUE INDEX validator_rules_pkey ON taska.validator_rules USING btree (id)`
- `CREATE INDEX validator_rules_transition_idx ON taska.validator_rules USING btree (transition_id)`
- `CREATE UNIQUE INDEX validator_rules_transition_rule_type_sort_uniq ON taska.validator_rules USING btree (transition_id, rule_type, sort_order)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0000-init.sql](../../workflow-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [workflow-service/src/main/resources/db/changelog/changes/0001-default-workflow-missing-transitions.sql](../../workflow-service/src/main/resources/db/changelog/changes/0001-default-workflow-missing-transitions.sql)

### workflow-service · taska.workflow_binding_changes

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL
- `project_id` `uuid` NOT NULL
- `workflow_id` `uuid` NOT NULL
- `actor_user_id` `uuid` NOT NULL
- `kind` `text` NOT NULL
- `request_id` `text` NOT NULL
- `node_id` `text` NOT NULL
- `channel` `character varying(32)` NULL
- `token_id` `uuid` NULL
- `command_json` `jsonb` NOT NULL
- `plan_json` `jsonb` NOT NULL
- `state` `text` NOT NULL
- `error_status` `text` NULL
- `error_message` `text` NULL
- `error_details` `jsonb` NOT NULL DEFAULT `'{}'::jsonb`
- `result_bytes` `bytea` NULL
- `reservation_released` `boolean` NOT NULL DEFAULT `false`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`

Ограничения:

- `workflow_binding_changes_channel_check`: `CHECK (channel IS NULL OR (channel::text = ANY (ARRAY['UI'::character varying, 'API_TOKEN'::character varying, 'MCP'::character varying]::text[])))`
- `workflow_binding_changes_kind_check`: `CHECK (kind = ANY (ARRAY['CREATE'::text, 'REPLACE_TYPES'::text]))`
- `workflow_binding_changes_pkey`: `PRIMARY KEY (id)`
- `workflow_binding_changes_project_id_actor_user_id_request_i_key`: `UNIQUE (project_id, actor_user_id, request_id)`
- `workflow_binding_changes_state_check`: `CHECK (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text, 'COMPLETED'::text, 'CANCELLED'::text]))`

Индексы:

- `CREATE UNIQUE INDEX workflow_binding_changes_pending_project ON taska.workflow_binding_changes USING btree (project_id) WHERE (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text]))`
- `CREATE UNIQUE INDEX workflow_binding_changes_pkey ON taska.workflow_binding_changes USING btree (id)`
- `CREATE UNIQUE INDEX workflow_binding_changes_project_id_actor_user_id_request_i_key ON taska.workflow_binding_changes USING btree (project_id, actor_user_id, request_id)`
- `CREATE INDEX workflow_binding_changes_recovery ON taska.workflow_binding_changes USING btree (updated_at, id) WHERE (reservation_released = false)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql](../../workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql)

### workflow-service · taska.workflow_bindings

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `project_id` `uuid` NOT NULL
- `issue_type` `text` NOT NULL
- `workflow_id` `uuid` NOT NULL
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `issue_type_generation` `uuid` NULL

Ограничения:

- `workflow_bindings_pkey`: `PRIMARY KEY (id)`
- `workflow_bindings_project_issue_uniq`: `UNIQUE (project_id, issue_type)`
- `workflow_bindings_workflow_id_fkey`: `FOREIGN KEY (workflow_id) REFERENCES taska.workflows(id) ON DELETE RESTRICT`

Индексы:

- `CREATE UNIQUE INDEX workflow_bindings_pkey ON taska.workflow_bindings USING btree (id)`
- `CREATE UNIQUE INDEX workflow_bindings_project_issue_uniq ON taska.workflow_bindings USING btree (project_id, issue_type)`
- `CREATE INDEX workflow_bindings_workflow_idx ON taska.workflow_bindings USING btree (workflow_id)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0000-init.sql](../../workflow-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [workflow-service/src/main/resources/db/changelog/changes/0005-issue-type-generation.sql](../../workflow-service/src/main/resources/db/changelog/changes/0005-issue-type-generation.sql)
- [workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql](../../workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql)

### workflow-service · taska.workflows

Класс: `TENANT_TARGET`. RLS текущей fresh-схемы: enabled=`false`, forced=`false`.

Колонки:

- `id` `uuid` NOT NULL DEFAULT `gen_random_uuid()`
- `name` `text` NOT NULL
- `version` `integer` NOT NULL DEFAULT `1`
- `is_active` `boolean` NOT NULL DEFAULT `true`
- `created_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `updated_at` `timestamp with time zone` NOT NULL DEFAULT `now()`
- `owner_project_id` `uuid` NULL

Ограничения:

- `workflows_pkey`: `PRIMARY KEY (id)`
- `workflows_version_chk`: `CHECK (version >= 1)`

Индексы:

- `CREATE INDEX workflows_owner_project_idx ON taska.workflows USING btree (owner_project_id, created_at, id)`
- `CREATE UNIQUE INDEX workflows_pkey ON taska.workflows USING btree (id)`

Источники (создание, изменения и затрагивающие SQL):

- [workflow-service/src/main/resources/db/changelog/changes/0000-init.sql](../../workflow-service/src/main/resources/db/changelog/changes/0000-init.sql)
- [workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql](../../workflow-service/src/main/resources/db/changelog/changes/0011-workflow-binding-operations.sql)

## SYSTEM: Liquibase

Для каждого из `admin-service`, `auth-service`, `issue-service`, `notification-service`, `oauth-server`, `project-service`, `workflow-service` существуют два engine-managed вида таблиц: `databasechangelog` и `databasechangeloglock` — **14 экземпляров**. Они не tenant-owned; нет `tenant_id`, RLS и бизнес-cleanup. Доступ только migration path; app/admin/job не получают DDL/изменение журналов. Конкретная схема определяется Liquibase config. Auth поддерживает совместимую историю `public` либо `taska`; runtime-схема не установлена этой проверкой.

`databasechangelog`:

**databasechangelog**

- `id` `character varying(255)` NOT NULL
- `author` `character varying(255)` NOT NULL
- `filename` `character varying(255)` NOT NULL
- `dateexecuted` `timestamp without time zone` NOT NULL
- `orderexecuted` `integer` NOT NULL
- `exectype` `character varying(10)` NOT NULL
- `md5sum` `character varying(35)` NULL
- `description` `character varying(255)` NULL
- `comments` `character varying(255)` NULL
- `tag` `character varying(255)` NULL
- `liquibase` `character varying(20)` NULL
- `contexts` `character varying(255)` NULL
- `labels` `character varying(255)` NULL
- `deployment_id` `character varying(10)` NULL

PK отсутствует; uniqueness не предполагается.

**databasechangeloglock**

- `id` `integer` NOT NULL
- `locked` `boolean` NOT NULL
- `lockgranted` `timestamp without time zone` NULL
- `lockedby` `character varying(255)` NULL

PK `(id)`; уникальный PK index. Singleton locking управляет Liquibase.

System определения — явно маркированный контракт metadata Liquibase, не утверждение об обнаруженных 14 runtime-таблицах. Поиск actual journals и сверка движка входят в rollout preflight, без удаления/слияния чужой истории.

## Вывод для реализации

Каждая из 55 таблиц получила ровно одно целевое описание в `existing_tables` target JSON. Новые таблицы перечислены полностью, включая global account notifications, grant/session/challenge persistence, attachment/avatar manifests, технические receipts и worker partitions. Нет требования делать эту инвентаризацию повторно в задачах разработчиков: им остаются миграции и код по конкретным определениям.
