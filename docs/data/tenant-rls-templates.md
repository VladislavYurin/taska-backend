# Общие RLS-шаблоны — TAS-308

Шаблоны находятся в `common-lib/src/main/resources/db/tenant/`. SQL cores пригодны для включения в НОВЫЕ Liquibase changesets; psql wrappers обеспечивают параметризацию и транзакцию для оператора. Ничего не регистрируется автоматически в существующих сервисных changelog. Внедрение таблиц/перенос данных — TAS-329/330/332, расширенная проверка executor/pool — TAS-309.

TAS-330 добавляет versioned `db/tenant/v2/protect-table.sql` и `v2/audit.sql` с одноимёнными psql wrappers. V2 принимает lowercase ASCII имена таблиц до63 символов (schema по-прежнему до32), включая существующую `issue_type_creation_workflow_receipts`. Для длинных имён защитный индекс получает ограниченное детерминированное имя с digest; проверка совпадения relation/tenant column сохраняется. Имена длиннее63 отвергаются. V1 остаётся байт-в-байт прежней: её ресурсы участвуют в checksum уже применённых Liquibase changesets. Новые workflow changesets явно включают v2; старые потребители автоматически не переключаются. Bootstrap используется прежний, `db/tenant/bootstrap.psql`.

| Файл | Назначение |
|---|---|
| bootstrap.sql / bootstrap.psql | Cluster bootstrap: роли, schema owner, минимальные database/schema/default privileges |
| protect-table.sql / protect-table.psql | UUID tenant column NOT NULL, ENABLE/FORCE RLS, USING/WITH CHECK, tenant-leading index, grants |
| audit.sql / audit.psql | Fail-closed проверка ролей, memberships, owner, grants, policy predicates и tenant FK |
| example-migration.sql / example-migration.psql | Только fixture/example: tenant-leading unique/index и composite FK; не production migration |

Bootstrap выполняется контролируемым администратором кластера на выделенной БД сервиса. Он отзывает PUBLIC CONNECT/CREATE/TEMPORARY для этой БД; совместная с другими приложениями БД требует отдельного плана. Разрешённые short names: auth, tenant, billing, project, workflow, issue, notification, admin, oauth. В v1 schema/table identifiers ограничены lowercase ASCII длиной до32; v2 расширяет только table identifiers до63. Пароли скрипт не устанавливает; новые LOGIN-роли получают credentials отдельно из secret store. Bootstrap запрещает несовместимые существующие role attributes/memberships, а не исправляет их молча. Повторный bootstrap безопасен для штатной конфигурации.

| Роль taska_SERVICE_SUFFIX | Привилегии |
|---|---|
| migrator | NOLOGIN, владелец schema/tables, DDL; без SUPERUSER/BYPASSRLS/CREATEROLE/CREATEDB/REPLICATION |
| deployer | LOGIN, NOINHERIT; только CONNECT и SET ROLE migrator (INHERIT FALSE, ADMIN FALSE); не используется приложением |
| app | LOGIN, NOINHERIT/NOBYPASSRLS; CONNECT/USAGE, явный профиль read_write, append_only или read_only защищённой таблицы |
| admin | Отдельный LOGIN/pool; только SELECT защищённой таблицы; RLS продолжает действовать |
| job | Отдельный LOGIN/pool; нет автоматических table DML, нужные grants задаёт owner migration |
| dispatcher | CONNECT/USAGE; нет business-table grants; разрешения на SYSTEM partition/lease table задаются отдельно |

Ни одна runtime роль не член других ролей и не имеет входящих membership grants. Нет DDL/TRUNCATE/TRIGGER/REFERENCES, grant options или владения таблицами. Default privileges не выдают доступ к новым таблицам/sequence; PUBLIC EXECUTE для будущих функций владельца отзывается на уровне БД (schema-local REVOKE не отменяет глобальный default). Не создаются SECURITY DEFINER helpers, которые приложение могло бы вызывать для смены grants.

Пример запуска через приватные libpq service profiles, без секретов в аргументах:

```sh
psql 'service=taska-bootstrap' -v service=project -v schema=taska \
  -f common-lib/src/main/resources/db/tenant/bootstrap.psql
psql 'service=taska-project-deploy' -v service=project -v schema=taska \
  -v table=projects -v tenant_column=tenant_id -v app_access=read_write \
  -c 'SET ROLE taska_project_migrator' \
  -f common-lib/src/main/resources/db/tenant/protect-table.psql
psql 'service=taska-bootstrap' -v service=project -v schema=taska \
  -v table=projects -v tenant_column=tenant_id -v app_access=read_write \
  -f common-lib/src/main/resources/db/tenant/audit.psql
```

В Liquibase owner changeset (`runInTransaction=true`) перед SQL core установить `set_config('taska.guard_service','project',true)`, `taska.guard_schema`, `taska.guard_table`, `taska.guard_column` и `taska.guard_app_access` (read_write/append_only/read_only). Затем включить `db/tenant/protect-table.sql` с `splitStatements=false`; psql wrappers не включать в Liquibase. Точно так же audit.sql использует taska.audit_service/schema/table/column/app_access. Bootstrap не запускается под runtime или автоматически вместе с приложением. Не переписывать уже применённые changesets.

Политика: `tenant_column = NULLIF(current_setting('app.tenant_id',true),'')::uuid`, одинаковая в USING/WITH CHECK для app/admin/job, FORCE RLS. Обычный tenant-column — tenant_id; registry tenants использует id. Missing/empty context даёт ноль строк; невалидный UUID вызывает отказ, global fallback отсутствует. Не существует SQL-флага app.is_admin для обхода. Дополнительная permissive policy не допускается. Шаблон поддерживает обычные таблицы; partitions/views требуют отдельного решения и проверки.

Перед защитой существующей таблицы owner migration выполняет backfill и добавляет tenant-leading business unique/FK из target-model.json. Шаблон не угадывает бизнес-ключи. Пример `(tenant_id,project_id) REFERENCES projects(tenant_id,id)` предотвращает cross-tenant FK, даже когда внутренние FK-проверки обходят RLS. Audit отвергает non-composite FK к уже защищённому tenant parent. Глобальные identity/catalog таблицы — явные исключения с отдельным обоснованием и grants; NULL tenant не является исключением. После подключения всех таблиц аудит обязателен до выдачи credentials/открытия traffic.

Таблица должна принадлежать migrator и иметь UUID tenant column. NULL данные, чужие policies, column ACL и конфликтующий named index приводят к атомарному отказу. Повтор protect-table восстанавливает минимальные grants: service-specific job grants нужно повторить в той же owner migration после шаблона. FORCE RLS действует и на migrator: backfill нужно выполнять до защиты либо отдельной явно проверенной migration policy в контролируемой фазе. В приложении использовать executor TAS-307 и явные tenant predicates.

Административный путь B02: gateway/owner service проверяет GLOBAL_ADMIN, MFA/grant и target tenant; после этого выбирает отдельный admin read pool. Mutations исполняет owner service command с доменной авторизацией/аудитом. Клиент не выбирает PostgreSQL роль через HTTP/SQL-параметр; runtime не может SET ROLE в admin/migrator/deployer. Бизнес-bypass администратора не отменяет tenant RLS или целостность FK.

Проверка `TenantRlsTemplatesIntegrationTest` запускает shipped wrappers и SQL cores через psql внутри отдельного PostgreSQL16 Testcontainer. Runtime проверки открывают настоящие LOGIN-сессии (session_user=runtime), не SET ROLE из superuser-сессии. Проверяются чтение/DML/FK, DDL/TRUNCATE/role escalation, owner FORCE RLS, grant/policy drift и атомарный отказ. Это проверка шаблонов, а не заявление о включённой RLS в существующих production таблицах.

App DML profile обязателен: read_write — SELECT/INSERT/UPDATE/DELETE, append_only — SELECT/INSERT, read_only — SELECT. Профиль выбирается owning migration по целевой модели; blanket CRUD для append-only ledger не применяется. Audit проверяет ровно заданный профиль.

Диагностика bootstrap перечисляет лишних incoming members. Регрессии отдельно подтверждают materialized default ACL, перепутанный parent FK order и конфликт named index на другой таблице: такие конфликты завершаются отказом без частичного включения RLS.
