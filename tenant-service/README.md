# tenant-service — TAS-310/311/312

Сервис владеет реестром компаний. `TenantService.CreateTenant/GetTenant` реализуют подмножество принятого [B2B protobuf](../docs/b2b/contracts/b2b.proto), опубликованное в `grpc-common-lib/src/main/proto/v1/tenant-registry.proto` с сохранением имён и номеров полей. Публичные HTTP endpoints ещё не подключены: gateway должен сначала получить глобальные/tenant session adapters и provisioning flow. Статический REST OpenAPI существующей системы не расширяется недоступным маршрутом.

## Membership authority — TAS-311

`TenantService.ListTenantMembers` возвращает metadata участия. Поля display_name/email/avatar_path остаются незаполненными: directory enrichment и публичный HTTP adapter принадлежат TAS-321. Номера полей Membership/Page взяты из blueprint. Membership.id — стабильный UUIDv8 от tenant/user с namespace taska:membership:v1; одна global identity имеет разные membership IDs в A/B. В mutation routes ключом остаётся user_id внутри tenant.

Список требует WEB_TENANT session либо USER_PAT/USER_OAUTH credential, read scope, signed tenant/action listTenantMembers и текущую ACTIVE actor membership с совпадающей signed membership generation. Компания должна быть ACTIVE. GLOBAL_ADMIN сам по себе не даёт bypass; отдельный административный путь — TAS-346. Список включает ACTIVE/SUSPENDED; REMOVED tombstones доступны только служебной authority. Порядок `(joined_at,user_id)` задаёт время вступления и уникальный tie-break; default50/max100, keyset без OFFSET. Следующие mutation задачи должны атомарно увеличивать row version и tenants.membership_version. Изменение общего поколения инвалидирует старую страницу: клиент начинает чтение заново.

Cursor подписан HMAC-SHA256, versioned, TTL15min. Он привязан к actor/tenant, actor и membership generations, credential kind/session/credential/grant/grantGeneration, sorted scopes, page size и фиксированным filters/order. Текущая membership проверяется на каждой странице. Пустой/подменённый/истёкший/чужой cursor даёт VALIDATION_FAILED, отозванная actor membership — concealed NOT_FOUND. Общий для replicas случайный ключ Base64 минимум32bytes задаётся TENANT_MEMBERSHIP_CURSOR_KEY. Пустая конфигурация отключает list с POLICY_NOT_CONFIGURED; backend reads от этого ключа не зависят. Некорректный непустой ключ останавливает старт без вывода его значения. Ротация ключа инвалидирует старые cursors. Ключ не хранить в Git/логах.

Внутренний `TenantMembershipService` имеет два read-only RPC:

| RPC | Ответ |
| --- | --- |
| GetMembership | Актуальные role/state/version, tenant_membership_version, owner flag и joined_at для одного tenant/user; отсутствующий membership — RESOURCE_NOT_FOUND |
| BatchValidateMemberships | 1..100 distinct canonical user UUIDs одного tenant, один результат на входной user в том же порядке; found=false без membership для отсутствующего, включая существующего только в другой компании |

Оба требуют verified SERVICE, scope memberships:read, точные target tenant/action и issuer из server allowlist TENANT_MEMBERSHIP_READERS. Defaults: auth-service, api-gateway, project-service, workflow-service, issue-service. mTLS peer, подпись и live caller→RPC edge проверяет общий RpcContextSecurity до handler. SERVICE authority проверяет служебный purpose и не вызывает membership validation рекурсивно для собственной аутентификации. Human actor может отсутствовать. ACTIVE membership caller не требуется: служебный read должен получать SUSPENDED/REMOVED с актуальной generation для отзыва доступа. Мутации участника описаны ниже.

Batch возвращает tenant lifecycle и общее membership generation; каждый найденный snapshot содержит индивидуальную version и то же общее generation. State не кешируется. Строки и обе generations читаются одним SELECT snapshot, включая LEFT JOIN для unknown users. Для бизнес-действия потребитель дополнительно проверяет lifecycle/capabilities и project ACL; found/ACTIVE сами не выдают рабочие права. DELETED/неизвестный tenant скрывается как TENANT_NOT_FOUND. Ответы не содержат credentials, display names, email или данные другой компании.

Internal proto добавлен к blueprint без изменения старых field numbers. Миграция0002 добавляет partial `(tenant_id,joined_at,user_id)` index для видимых состояний; changeset0001 не меняется. Обязательный TenantMembershipIntegrationTest проверяет A/B states/versions, HMAC и revocation/rejoin, natural keyset plan, отсутствие writes/outbox при чтении и реальный mTLS/JWS read RPC через production policy map.

## Изменение роли и выход — TAS-312

`TenantService.SetTenantMemberRole` и `LeaveTenant` опубликованы во внутреннем protobuf с номерами полей из B2B blueprint. Публичный HTTP adapter пока отсутствует; эти методы нельзя вызывать через существующий REST gateway. Human caller требует verified tenant/action, WEB_TENANT session либо USER_PAT/USER_OAUTH credential, `write` scope и текущую ACTIVE membership с совпадающей signed generation. Для назначения/снятия роли нужен собственный `TENANT_ADMIN` в той же компании. Обычный GLOBAL_ADMIN не получает неявный bypass. `LeaveTenant` требует явное `confirmed=true`; он действует только на самого caller.

Обе команды требуют UUID Idempotency-Key. Смена роли требует `expected_version` membership (аналог If-Match); выход без If-Match использует подписанную membership generation actor. Устаревшая generation активного actor даёт `TOKEN_REVOKED` (401), неактивный actor — `MEMBERSHIP_INACTIVE` (403), неактивный target при смене роли — `INVALID_STATE` (409). Одна tenant-row блокировка сериализует выбор actor/target, проверку owner и число ACTIVE TENANT_ADMIN, изменение membership, рост `tenants.membership_version`, receipt, audit и outbox. Владелец не может потерять роль или выйти до отдельного transfer/delete flow; последнего ACTIVE TENANT_ADMIN снять нельзя. Смена роли/выход увеличивает row version. Повтор с тем же ключом и payload возвращает сохранённый результат без новой записи; другой payload с тем же ключом даёт `IDEMPOTENCY_CONFLICT`. Успешное фактическое изменение создаёт `TenantMembershipChanged` outbox event с tenant/user/actor UUID, ролью, состоянием и результатом, без credentials или directory данных. Append-only `membership_audit` сохраняет actor/tenant/target, старое и новое состояние, версию и результат каждой принятой команды. No-op сохраняет receipt/audit, но не создаёт событие и не меняет версии. Миграция0003 добавляет защищённую tenant RLS audit таблицу; старые changesets не изменяются.

Выход сразу делает membership REMOVED и отзывает старую generation. Его повтор с прежним credential может быть отвергнут на live-auth boundary после отзыва; повтор не создаст второе изменение. Owner transfer — TAS-473. Эти RPC не означают готовый публичный B2B self-service или доступность на общем стенде.

## Suspend, remove и reactivation — TAS-323

`TenantService.SetTenantMemberState` принимает только `ACTIVE|SUSPENDED`; `RemoveTenantMember` выполняет переход `ACTIVE|SUSPENDED → REMOVED`. Оба метода сохраняют field numbers blueprint, требуют UUID Idempotency-Key и `expected_version`, проверяют WEB_TENANT/USER token с `write`, точные signed tenant/action и текущую ACTIVE membership actor. Собственный `TENANT_ADMIN` обязателен; GLOBAL_ADMIN не даёт неявный bypass. Owner и последний ACTIVE TENANT_ADMIN защищены под tenant-row lock. Ошибка validation/version/guard не меняет membership, quota, audit, receipt, outbox или компанию B.

`SUSPENDED → ACTIVE` всегда устанавливает роль `MEMBER`: прежняя TENANT_ADMIN не возвращается, новые project grants и назначения требуют отдельных команд. Возврат в ACTIVE синхронно отзывает старую membership generation и не оживляет старые project grants. Suspend сохраняет место; remove пересчитывает SEAT usage в той же транзакции, поэтому место освобождается ровно один раз и резервы приглашений не освобождаются повторно. Команда, audit, tenant membership generation, quota projection и outbox коммитятся одной транзакцией.

`TenantMembershipChanged` schema v2 сохраняет прежние поля и добавляет `previousVersion`, `revokedAt` и `cleanupScopes`. Для suspend/remove cleanup scopes — `PROJECT_GRANTS` и `ISSUE_ASSIGNMENTS`; потеря или понижение TENANT_ADMIN добавляет `PENDING_INVITATIONS`. Это durable контракт для владельцев project/issue/auth: поздний retry обязан сравнить scope generation/timestamp с `previousVersion`/`revokedAt`, поэтому не удаляет права или исполнителя, назначенного после reactivation/rejoin. Старые project grants уже отфильтровываются по `root_membership_version`. Публикация tenant outbox в Kafka и активация downstream cleanup workers остаются инфраструктурной границей, а не доказываются этой транзакционной реализацией; публичный REST также не добавлен.

Миграция `0014-membership-lifecycle.sql` расширяет append-only action allowlist для `setTenantMemberState` и `removeTenantMember`; уже применённый changeset `0003` не изменяется.


Для replay успешного self-leave mutation service читает только собственный сохранённый receipt до проверки уже отозванной membership generation. Gateway/trust adapters TAS-316/317 должны предоставить узкий путь receipt-only replay; обычная проверка актуального членства для остальных действий остаётся обязательной. Эта реализация native RPC сама не подтверждает публичный REST replay после отзыва tenant access.
## Создание и повторы

Create принимает только проверенный контекст `WEB_GLOBAL` с actor/session, без tenant и с action `createTenant`. Body не задаёт владельца, lifecycle, стоимость или deadline. `PublishedPlans` возвращает только опубликованную публичную immutable версию тарифа (adapter — TAS-313); client planVersion UUID сам по себе не доказывает его доступность. `FreeAdmission.reserve` — локальная атомарная операция TAS-472 на переданном DatabaseClient, не удалённая проверка. Без этих providers создание отклоняется до commit; paid никогда не вызывает FREE admission.

Для входной глобальной authority `TenantCreationTransaction` устанавливает только локальный tenant scope для записи registry; это не выпуск сессии и не выдача прав компании. Выполнение идёт через общий TenantTransactionExecutor: set_config transaction-local, одна connection, commit перед ответом, rollback/cancel cleanup. Каталог разрешается между короткими транзакциями, вне pooled transaction; inside callback нет сетевого RPC.

Стабильный UUIDv8 компании получается из SHA-256 доменного namespace `taska:createTenant:v1`, actor UUID и UUID Idempotency-Key (length-prefixed UTF-8; первые 128 бит с UUID version/variant). Алгоритм после публикации не меняется: это durable routing для pre-tenant команды без глобального каталога/блокировок или nullable tenant в dedupe. Key остаётся scoped к actor. UUID не является секретом или механизмом авторизации.

В tenant row сохраняются immutable `creation_actor_id`, `creation_request_hash` (SHA-256 canonical JSON name/planVersionId/operationId), `creation_operation_id`, `plan_version_id`, kind и deadline. `INSERT ON CONFLICT` сериализует параллельные попытки: разные payload с тем же ключом дают IDEMPOTENCY_CONFLICT, один payload возвращает один tenant/operation. Replay проверяет текущую ACTIVE membership, работает при retirement/недоступности каталога и после удаления response-cache. TTL idempotency cache24h не удаляет durable creation facts; их purge должен учитывать доменный dedupe в TAS-520.

В одной транзакции создаются tenant, ACTIVE/TENANT_ADMIN owner membership, outbox `TenantCreationRequested` и idempotency response. Циклический composite FK owner→membership проверяется на commit. Event содержит IDs/planVersion, не название компании или credentials. Kafka publisher — TAS-318; возобновляемая saga, durable worker и фактическое чтение операции реализованы TAS-337. Первый ответ возвращает PENDING; ACTIVE выставляется только после готовности обязательных шагов.

Paid начинается AWAITING_PAYMENT с `creationDeadline=createdAt+30d`, `PENDING_FIRST_PAYMENT`, без покрытия/рабочих прав. FREE остаётся PROVISIONING после атомарного admission, без paid deadline. Retrying не продлевает deadline. Trigger запрещает изменение исходных creation facts даже прямым SQL. Get требует совпадение signed tenant и request tenant, action getTenant и ACTIVE membership; чужой/удалённый tenant скрывается как TENANT_NOT_FOUND. Это чтение metadata, оно не обходит ограничения рабочих операций. Административный bypass — отдельный доверенный путь TAS-346, обычный GLOBAL_ADMIN не даёт его автоматически.

## Trust и запуск

`RpcContextSecurity` должен получить настоящие Keys, shared ReplayGuard и online Authority adapters TAS-306. Authority обязан привязать исходную сессию к зарегистрированному действующему пользователю, RPC/action/scope и актуальным generations; одного наличия account недостаточно. TenantServerInterceptor проверяет message-bound JWS и mTLS SPIFFE peer; registry, membership mutation и quota routes внесены в явный allowlist. Без trust provider все gRPC calls получают UNAVAILABLE до handler, plaintext не становится fallback. Liveness/DB health доступны отдельно. Этот foundation ещё не означает доступный self-service на общем стенде.

База выделена сервису. До первого старта оператор выполняет:

```sh
psql -X -v ON_ERROR_STOP=1 -f infra/tenant/bootstrap.psql
```

PGHOST/PGDATABASE/PGUSER и credential store задаются оператором извне. Bootstrap общий TAS-308 создаёт NOLOGIN migrator, LOGIN deployer NOINHERIT с разрешённым SET ROLE и непривилегированный app; отдельная закрытая `tenant_liquibase` принадлежит migrator. Пароли provision отдельно. В приложении Liquibase подключается deployer и явно переключается на migrator; runtime использует только app. Перед регистрацией бизнес-service startup guard отвергает owner/superuser/BYPASSRLS/role memberships. Новая миграция создаёт шесть таблиц TAS-310, применяет shared FORCE RLS templates к пяти tenant tables; global_outbox не получает app grants. Journal недоступен runtime; demo accounts нет.

Переменные: `TENANT_SPRING_R2DBC_{URL,USERNAME,PASSWORD}`, `TENANT_SPRING_LIQUIBASE_{URL,USERNAME,PASSWORD}`, `TENANT_{SERVER_PORT,GRPC_SERVER_PORT}` (8089/9099). Секреты обязательны, штатных паролей нет. Для отдельного локального Compose заполнить три `TENANT_{OPERATOR,APP,DEPLOY}_PASSWORD` в приватном окружении, затем:

```sh
docker compose -p taska-tenant-local -f infra/tenant/compose.yml up -d --build
```

Compose поднимает исключительно свои PostgreSQL/service, доступные по loopback. Инициализация выполняется только для новой private DB. Этот stack не включён в общий deploy-stage: активация foundation до подключения downstream/trust не является задачей TAS-310. Не монтировать shared production data directory. Для остановки своего stack: `docker compose -p taska-tenant-local -f infra/tenant/compose.yml down`.

## Проверки

```sh
python3 scripts/harness.py check --module tenant-service
```

PostgreSQL16/Testcontainers, фактический Liquibase с deployer→migrator, app LOGIN и повтор применения changelog. Проверяются atomic create, конкурентный retry/conflict, immutable deadline, cache eviction и catalog retirement, независимые actors, FREE/paid separation, cancellation rollback, membership isolation, отсутствующая trust и запуск Spring Boot/HTTP health на чистой миграции. `RegistryTestContext` существует только в test sources; production не принимает body context за verified authority. Дополнительно сам registry adapter проверяется по настоящему loopback mTLS/JWS: create/get, payload tampering, replay и чужой actor; openssl создаёт временные credentials. Базовые security-инварианты повторяются grpc-common-lib suite. Runtime jar не содержит test helper. Планы и service repository контракты будущих сущностей проверяются задачами их реализации.

## Platform configuration — TAS-462

[Typed global business flags](PLATFORM-FLAGS.md) add protected ListPlatformFlags/UpdatePlatformFlag RPCs, immutable version/audit history, idempotent optimistic updates and transactional global events. Paid creation now snapshots its 30-day default from the DB registry; existing deadlines and retries retain their original value. Public capabilities and flag enforcement in other domains remain separate tasks.

## Private merchant policy — TAS-519

The [commercial configuration authority](../billing-service/COMMERCIAL-READINESS.md) adds protected Get/Publish/ResolveMerchantConfiguration RPCs and one private JSON policy in the platform registry. Scalar flag listing excludes this document. Administrative responses and version events contain metadata only; full configuration is restricted to the verified billing-service reader.

## FREE eligibility — TAS-472

[Atomic FREE eligibility](FREE-ELIGIBILITY.md) implements the creation admission and protected billing admission, WEB_GLOBAL creation options and internal lawful-disposition RPCs. Identity/KMS/purpose preparation happens outside SQL; the marker commits with creation or an admission receipt. Deletion minimization retains only purpose-bound matching data. Real primary providers, gateway and offboarding/plan orchestration remain separate activation dependencies.

## Durable provisioning — TAS-337

[Durable saga, operation lookup, recovery, protected RPCs and local service-path E2E](PROVISIONING.md). Registry creation now stores the actual operation and dispatch entry atomically; FREE activates only after workflow/billing preparation. Public gateway integration and deployment activation remain separate.

[Atomic seat quotas and GetTenantQuotas](SEAT-QUOTAS.md) describe TAS-322 transactional primitives, authorization requirements and activation limits.

[Storage reservations and primary manifests](STORAGE-QUOTAS.md) describe TAS-347 byte accounting, pinned S3 proof, bounded recovery and the TAS-365/TAS-367 integration boundaries.
