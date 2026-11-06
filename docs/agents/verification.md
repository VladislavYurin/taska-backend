# Матрица проверок

Базовые проверки TAS-452 сохранены в [versioned manifest](../../scripts/harness/verification.json); CLI и CI подключены в [TAS-453](https://jira.ozero.dev/browse/TAS-453). При изменении CI/POM обновлять manifest и эту матрицу в том же PR.

## Единый CLI

Python 3.9+, стандартная библиотека, установка пакетов не нужна. Запуск из корня checkout:

```sh
python3 scripts/harness.py --help
python3 scripts/harness.py doctor --tier static
python3 scripts/harness.py doctor --tier java
python3 scripts/harness.py plan --base origin/develop
python3 scripts/harness.py docs-check
python3 scripts/harness.py check --module issue-service
python3 scripts/harness.py check --group harness
python3 scripts/harness.py check --group config
python3 scripts/harness.py check --check mcp-server.it
python3 scripts/harness.py check --all
```

`doctor` только читает capabilities: git/Python/Java/Node/Docker/Compose и наличие wrapper. Он не запускает Maven wrapper и не скачивает зависимости. Пробы показывают доступность инструментов, а не успешную компиляцию проекта. Для Java проверяется версия не ниже release из POM; требуемый CI JDK выводится отдельно. Tier `static` не требует Docker/Node, `config` требует их, `all` проверяет все capabilities.

`plan` сравнивает merge-base указанного commit/ref с текущим рабочим деревом, включая tracked удаления и untracked файлы вне ignore. Список checks сопровождается причинами: module changes, consumers из POM, event/trust rules, консервативный fallback для неизвестного общего файла. Изменения модуля распространяются на downstream; upstream для сборки добавляется отдельно, без распространения impact в соседние сервисы. Проверка event semantics и выбор E2E остаются обязанностью агента. План не выполняется автоматически.

`check --module` запускает `clean verify` и специальные IT из manifest с зависимостями. `--check` выбирает конкретный стабильный check ID, зависимый verify выполняется автоматически. Можно повторять `--check`. `--all` выполняет все 16 модулей и integration/config/harness/docs проверки последовательно. Требуются все prerequisites выбранного набора; нет скрытой установки инструментов или автоматического изменения `.env`.

Существующие groups: `java`, `config`, `harness`, `docs`. TAS-454 добавляет отдельные `e2e` и `cleanup`: [команды, конфигурация и ограниченный scope](e2e.md). Отчёт JSON выводится в stdout, прогресс ID/status — в stderr. Сохранять stdout можно в ignored `target/` или внешний каталог; credentials в аргументы не передавать. Verification-команды не создают постоянных raw logs (E2E startup diagnostics сохраняются только приватно); диагностика ограничена и санитизируется. Реальные subprocesses могут создавать свои build outputs и Maven cache.

Коды завершения: 0 — все выбранные проверки выполнены; 1 — failure/timeout; 2 — неверный вызов или manifest; 3 — отсутствует prerequisite; 4 — неполный набор (например, selected Maven tests skipped). При сочетании исходов приоритет 1, затем 3, затем 4. Независимые checks продолжаются после ошибки; зависимый check блокируется. Нет сетевого доступа к Jira/GitHub/stage, если его не выполняют уже существующие build/config инструменты. На Windows использовать Unix-совместимую среду/WSL: управление группами процессов рассчитано на Linux/macOS.

`docs-check` проверяет POM modules, ссылки точки входа, наличие выбранных классов, согласованность argv/report selectors, no-test exemptions и привязку CI к manifest. Не обращается к внешним ссылкам и не доказывает семантическую полноту документации. AGENTS и сценарии остаются предметом review.

## Предварительная проверка

Запускать из корня checkout. `git status --short --branch`, `java -version`, `./mvnw -version` устанавливают рабочую ветку и используемый JDK. Для Testcontainers и Compose нужны доступный Docker daemon и Compose v2. Проверить `docker version` и `docker compose version`; отсутствие daemon — отсутствие предпосылки, а не успешный тест.

`pom.xml` задаёт Java release 21; PR CI запускает Temurin 25. Runtime образы задаются Dockerfile/Compose, их проверяют отдельно. Не считать JDK процесса сборки и release байткода одним параметром. Для воспроизведения CI использовать JDK 25; для локального MCP Compose runner следовать [его требованиям](../../mcp-server/README.md).

Не выполнять `cp .env.docker.example .env` поверх существующей конфигурации. На новом checkout создать `.env` из шаблона после проверки отсутствия файла; не выводить его содержимое. Для проверки локального Compose:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile infra --profile services config --quiet
```

OAuth overlays имеют отдельные требования; см. [OAuth stage](../../infra/oauth-stage/README.md). Вывод полного `compose config` может раскрыть подставленные секреты, поэтому для проверки использовать `--quiet`.

## Базовые Java-команды

Вместо `issue-service` подставлять реально затронутый модуль из корневого POM:

```sh
./mvnw -B -ntp -pl issue-service -am verify
```

`-am` собирает зависимости выбранного модуля, но не все зависящие от него модули. Изменения общих библиотек требуют отдельной проверки потребителей. CI использует `clean verify` для каждой записи своей матрицы.

`StorageQuotaIntegrationTest` в tenant-service использует test-only `quota-fixture` issue-service,
два PostgreSQL и versioned S3 Testcontainer; это проверка production stores через mTLS/JWS.
Добавленная test dependency включает issue-service в Maven reactor `-am`. Проверка legacy
storage-lib `S3StorageClientIT` по-прежнему запускается явно, чтобы сохранить avatar contract.

Обычный Surefire выбирает в том числе `*IntegrationTest`, но не `*IT`. Для выбранных IT использовать точные имена классов, например:

```sh
./mvnw -B -ntp -pl issue-service -am test \
  -Dtest=ListIssuesForBoardIT,IssueTypeMoveIT,WorkflowBindingMigrationIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`failIfNoSpecifiedTests=false` нужен для upstream-модулей без выбранных классов. Он также может скрыть опечатку в selector: после запуска проверить наличие отчёта и ненулевое число tests в целевом модуле. Exit code 0 без выполненного целевого теста не закрывает проверку. CLI сравнивает fingerprints отчётов до/после команды; каждый ожидаемый IT-класс должен иметь свежий XML с ненулевым числом tests, без failures/errors. Skipped tests дают PARTIAL. Старые отчёты не удовлетворяют selector. Для verify без тестов разрешено только явно описанное исключение manifest.

## Что проверять при изменении

| Изменение | Базовый verify | Дополнительная проверка |
|---|---|---|
| REST endpoint, DTO, mapping, ACL | `api-gateway` и сервис-владелец | OpenAPI parity, успех/валидация/отказ, неизменность после отказа, применимые E2E |
| Proto или `common-lib` | производитель и все найденные потребители | сериализация/совместимость, семантика enum и отсутствующих значений; широкая CI-матрица |
| Проекты и типы | `project-service` | `IssueTypeDeletionIT`, `IssueTypeCreationIT`; связанные workflow/issue проверки |
| Project tenant storage | `project-service` | обязательный `TenantProjectStorageIntegrationTest`: app LOGIN/RLS/FK, A/B, archive/rollback, query plans и чистый профиль |
| Workflow и привязки типов | `workflow-service`, `project-service`, `issue-service`, gateway при изменении REST | reservation/binding IT из списка ниже; версии, повторы, перенос/восстановление |
| Workflow tenant storage/templates | `workflow-service`, common RLS templates | обязательный `TenantWorkflowStorageIntegrationTest`: app LOGIN/RLS/FK, parallel provision/instantiate, immutable snapshots, rollback и profile health; `TenantRlsTemplatesIntegrationTest` проверяет v1/v2 |
| Доска, issue migration | `issue-service` | `ListIssuesForBoardIT`, `IssueTypeMoveIT`, `WorkflowBindingMigrationIT` по изменённой логике |
| Блокировка приглашённого | `auth-service`, gateway при изменении REST | `InvitedUnblockIT`; online access и сохранность credentials |
| Outbox retry/admin | `admin-service`, `kafka-lib` и затронутые владельцы | `OutboxRetryServiceIT`, `OutboxRetryRepositoryIT`; аудит и повторный вызов |
| MCP | `mcp-server`, gateway и владелец изменённой операции | snapshot, `McpClientSecurityIT`, локальный Compose E2E для сквозного поведения |
| OAuth AS и social login | `oauth-server`, `auth-service`, gateway/MCP по impact | protocol/security тесты, browser flow и stage только при наличии условий |
| Доставка уведомлений | `notification-service`, producer/common/kafka по impact | дедупликация, очереди и E2E; постановка в очередь не доказывает доставку |
| Liquibase/SQL | сервис-владелец и потребители изменённой схемы | реальный PostgreSQL: новая БД, upgrade с предыдущей схемы, ограничения и транзакции; план rollout/recovery |
| Storage | `storage-lib` и вызывающие сервисы | presign/confirm/чужой файл, размер/MIME, фактический storage E2E |
| Infra/Compose/observability | конфигурационные тесты ниже | Compose quiet validation; специальные container/config проверки CI |
| AI review/Jira automation | Node tests ниже | fixture-тесты изменённого сценария, безопасное поведение на untrusted input |
| Документация | Java-тесты не обязательны при изменении только текста | ссылки, команды, соответствие источникам, отсутствие секретов/результатов прогонов |

Матрица задаёт минимальный выбор по поведению. Наличие имени теста в таблице не означает, что он проверяет каждый новый acceptance criterion: читать assertions и добавлять значимые проверки при пробелах.

## Явные IT текущего PR CI

Для каждой строки применима форма `./mvnw -B -ntp -pl MODULE -am test -Dtest=CLASSES -Dsurefire.failIfNoSpecifiedTests=false`:

| MODULE | CLASSES |
|---|---|
| `mcp-server` | `McpClientSecurityIT` |
| `admin-service` | `OutboxRetryServiceIT,OutboxRetryRepositoryIT` |
| `issue-service` | `ListIssuesForBoardIT,IssueTypeMoveIT,WorkflowBindingMigrationIT` |
| `project-service` | `IssueTypeDeletionIT,IssueTypeCreationIT` |
| `workflow-service` | `IssueTypeWorkflowReservationIT,WorkflowBindingChangeIT,WorkflowCreatedTypeBindingIT,WorkflowScopeCheckIT` |
| `auth-service` | `InvitedUnblockIT` |

Источник: [verification.json](../../scripts/harness/verification.json). [PR CI](../../.github/workflows/pr-check.yml) получает все 16 модулей из manifest и вызывает тот же `check --module`. `storage-lib` и `metrics-starter` теперь имеют отдельные matrix jobs. Для `kafka-lib` и `metrics-starter` явно указано отсутствие default Surefire test sources; появление таких тестов требует обновить ожидания manifest. CI проверяет документацию и Python/Node-тесты harness отдельной job.

## MCP Compose и snapshot

Изолированный runner уже существует:

```sh
python3 mcp-server/src/test/compose/run.py
```

Сначала сверить Docker endpoint и требования [MCP README](../../mcp-server/README.md). Runner собирает jar и поднимает собственные PostgreSQL/Kafka/RustFS и приложения. Его сценарий не покрывает доставку email/Telegram, storage blobs, полноценный admin-service, всех внешних MCP-клиентов или stage deployment. Он использует случайный Compose project и очищает свои ресурсы в `finally`.

`--keep` оставляет только успешный стенд для внешнего клиента; credentials остаются в ignored `mcp-server/target/mcp-e2e/<project>/credentials.json`. После проверки использовать документированный `--cleanup` с этим файлом. Не выводить его содержимое и не удалять чужие Compose projects.

Catalog snapshot — проверяемый контракт. Обновление `-DupdateMcpCatalogSnapshot=true` выполнять только после оценки intentional diff, затем повторить тест без update flag. Не обновлять snapshot автоматически ради зелёного теста.

## Конфигурация и автоматизация

Существующие локальные команды:

```sh
node --test infra/tests/*.test.js
python3 -m unittest discover -s infra/pgadmin -p 'test_*.py'
python3 -m unittest discover -s infra/oauth-stage -p 'test_*.py'
node --test .github/codex/scripts/*.test.js
```

Некоторые container-проверки opt-in и могут быть пропущены в обычном Python запуске. Текущий CI дополнительно выполняет:

```sh
PGADMIN_CONTAINER_TEST=1 python3 -m unittest discover -s infra/pgadmin -p 'test_container_startup.py'
```

Также CI проверяет синтаксис Promtail в local/stage через Docker; точный image и mounts брать из workflow. Не объявлять весь CI воспроизведённым после одних Node/Python тестов. Docker images PR собираются по label `docker`; skip этой job не доказывает сборку runtime image.

## E2E после ревью

1. Выбрать номера из [сценариев](../testing/backend-e2e-scenarios.md), связанные с diff, и добавить новые corner cases при изменении поведения.
2. Проверить окончательный head PR. Для локального стека подтвердить сборку его артефактов; для stage — revision каждого затронутого сервиса, health и отсутствие смены версии за время прогона.
3. Проверить учётные записи, роли, квоты, mailbox/Telegram/OAuth/storage prerequisites. Если их нет, перечислить недоступные сценарии. Не добывать секрет из БД для обхода публичного сценария.
4. Выполнить успех и отказы, проверить состояние после ошибки, очистить собственные фикстуры. HTTP через terminal и Swagger эквивалентны при одинаковом запросе.
5. Сохранить санитизированные результаты вне Git с привязкой к head, версии среды и сценарию. После изменения кода повторить затронутые проверки; после смены версии среды не приписывать результат предыдущему deployment.

Документационный PR не требует прогонять весь backend на общем стенде: проверка документации проводится после ревью, а runtime E2E отмечается как неприменимый с причиной. Если меняется runner/команда или исполняемая конфигурация, проверить её реальное выполнение в подходящей изолированной среде.

TAS-306: grpc-common-lib теперь имеет обязательные Surefire tests (RpcContextSecurityTest и TenantTransportTest); no-test exemption снят. Для локального mTLS-теста нужен openssl и возможность открыть loopback port. Ключи генерируются в test temporary directory.

TAS-307: `common-lib.verify` включает `TenantTransactionExecutorIntegrationTest` (PostgreSQL16/Testcontainers, R2DBC pool maxSize=1, непривилегированная RLS-роль). Docker обязателен; prerequisite также указан для grpc-common-lib.verify и kafka-lib.verify, запускающих common-lib tests через -am. Тест проверяет transaction-local settings, commit/error/cancel cleanup и блокировки; выполняется стандартным Surefire без отдельного IT selector. [Контракт executor](../data/tenant-transactions.md).

TAS-308: `TenantRlsTemplatesIntegrationTest` в common-lib.verify исполняет общие psql/SQL templates на PostgreSQL16 с настоящими runtime LOGIN sessions. Проверяет RLS, composite FK, role/grant/policy drift и default privileges. Требуется Docker; psql берётся из контейнера. [Шаблоны и применение](../data/tenant-rls-templates.md).

TAS-309: common-lib.verify дополнительно требует свежий `TenantIsolationContractIntegrationTest` report. Suite использует shipped TAS-308 templates, настоящий app LOGIN и executor: сложные SQL/batch/RETURNING, composite FK, nested/retry/cancel, одновременно занятые pool connections и естественные tenant-leading plans на 40 000 issues. [Переиспользуемая fixture, планы и ограничения](../data/tenant-isolation-suite.md). Fixture экспортируется только в test classifier; runtime jar не расширяет API доверенного контекста.

TAS-310: `tenant-service.verify` — новый обязательный Maven/Docker check; требует `TenantRegistryIntegrationTest`. Проверяет Liquibase из реального deployer→migrator, runtime LOGIN, конкурентный create/replay/conflict, FREE rollback, immutable deadline, membership isolation и Spring Boot health. [Запуск и границы foundation](../../tenant-service/README.md). gRPC contract additive: все consumers проверяет CI-матрица. Отдельный opt-in Compose — `infra/tenant/compose.yml`, общий deploy-stage этим PR не расширяется.

TAS-311: `tenant-service.verify` также требует свежий `TenantMembershipIntegrationTest`. Проверяются metadata list/get/batch, HMAC cursor expiry/binding/revocation, независимые A/B generations и один SQL snapshot при конкурентной записи. Реальный mTLS/JWS вызывает новые методы через production policy map. Keyset index добавлен отдельным changeset. [Контракт чтения](../../tenant-service/README.md#membership-authority--tas-311).

TAS-312: тот же `TenantMembershipIntegrationTest` проверяет role/leave RPC на PostgreSQL: ACTIVE admin, owner guard, stale version, идемпотентный повтор, отказ постороннему/неактивному actor, атомарный outbox и одновременный выход двух администраторов. Проверка публичного REST остаётся неприменимой до gateway adapter. [Контракт мутаций](../../tenant-service/README.md#изменение-роли-и-выход--tas-312).

## Billing catalog

`python3 scripts/harness.py check --module billing-service` requires fresh nonempty `BillingCatalogIntegrationTest`, `SubscriptionIntegrationTest`, `InvoiceIntegrationTest` and `FinancialLedgerIntegrationTest` reports. It exercises real PostgreSQL 16, Liquibase, app role/RLS, concurrent catalog mutations and mTLS/JWS production policies. See [billing-service](../../billing-service/README.md) for the delivered RPCs and activation boundaries. Public REST/PSP E2E is not established by these checks.

TAS-314 adds [subscription model verification](../../billing-service/SUBSCRIPTIONS.md): upgrade with unchanged catalog checksum, transactional coverage/idempotency/outbox, immutable terms, financial owner/admin guards, A/B isolation and real mTLS read RPCs. This does not establish external settlement/calendar/gateway readiness.

TAS-475 adds [immutable invoice verification](../../billing-service/INVOICES.md): issuance/replay concurrency, exact snapshot amounts, sealed lines, transactional rollback, financial access and real mTLS/JWS invoice RPCs. Commercial/assignment production adapters and public REST/PSP E2E remain separate.
TAS-462: `tenant-service.verify` additionally requires `PlatformFlagsIntegrationTest`: preceding-schema upgrade, typed B11 defaults, administrative scope, concurrent version/idempotency races, audit/outbox rollback, signed pagination and production mTLS/JWS policies. [Registry and activation boundaries](../../tenant-service/PLATFORM-FLAGS.md). Final review is followed by this protected-RPC integration rerun; public REST/stage B2B E2E needs the future gateway/trust adapters.

TAS-476: [financial ledger verification](../../billing-service/LEDGER.md) adds required `FinancialLedgerIntegrationTest`: money conservation, immutable facts/matches/allocations, concurrent returns, full allocation guards and tenant isolation. Settlement/payment/coverage orchestration and external PSP E2E remain separate.
TAS-519 adds required `MerchantConfigurationIntegrationTest` (tenant-service) and `CommercialReadinessIntegrationTest` (billing-service): private profile upgrade/audit/rollback, production mTLS/JWS policies, environment/credential guards, exact inclusive EUR/USD pricing, immutable FX and frozen historical terms. [Contracts and activation limits](../../billing-service/COMMERCIAL-READINESS.md). Actual PSP/FX-provider and public payment E2E require their separate adapters and operator inputs.

TAS-332: `issue-service.verify` requires fresh `TenantIssueStorageIntegrationTest`: PostgreSQL app LOGIN, shipped tenant migration/RLS, numbering/idempotency contention, atomic rollback and isolated profile health. [Storage contract and activation boundaries](../../issue-service/TENANT-STORAGE.md). Public tenant API/ACL remains TAS-343; lifetime quota admission remains TAS-460.

TAS-472 adds required `FreeEligibilityIntegrationTest`: migration4→5, atomic FREE create/rollback, owner-transfer races and same-company admission, dedicated versioned HMAC matching, annual review, lawful erasure/replay, minimization without identity reconstruction, a one-connection pool and production mTLS/JWS policies. [Contract and activation boundaries](../../tenant-service/FREE-ELIGIBILITY.md). Repeat these protected-RPC scenarios after final review; public REST/real KMS/offboarding E2E remain dependent on their adapters.

TAS-337: `tenant-service.verify` requires fresh `TenantProvisioningIntegrationTest` and `TenantProvisioningRpcIntegrationTest` reports. Its test-only reactor dependencies include production billing/workflow handlers. The local service-path E2E uses mTLS/JWS and three isolated PostgreSQL databases; it does not establish public gateway, live PSP or stage readiness. See [provisioning recovery and activation](../../tenant-service/PROVISIONING.md).

TAS-478: `tenant-service.verify` additionally requires `TenantCreationLifecycleIntegrationTest`; the production mTLS/JWS fixture includes the actual expiry scheduler and fake primary financial decisions. See [creation closure](../../tenant-service/CREATION-CLOSURE.md). Real settlement and public HTTP adapters are separate acceptance scopes.

TAS-359: `grpc-common-lib.verify` requires fresh `EntitlementProjectionIntegrationTest` (real PostgreSQL/RLS, restart, duplicate/reordered events, conflicting versions, bounded cache, membership revocation, clock expiry and fail-closed reconciliation) and `GrpcEntitlementSourceTest` (real mTLS/JWS, current service authority, tenant/minimum version and uncached membership/role revocation). Owner migrations also run under `TenantProjectStorageIntegrationTest`, `TenantIssueStorageIntegrationTest`, `TenantWorkflowStorageIntegrationTest` and `TenantMembershipIntegrationTest`. See [projection activation and contracts](../data/entitlement-projection.md). These isolated checks do not establish shared Kafka/stage/public REST readiness.

TAS-322: `tenant-service.verify` requires `SeatQuotaIntegrationTest` (real PostgreSQL/Liquibase/RLS, last-seat concurrency, unique ACTIVE/SUSPENDED usage, reserve/commit/release/expiry replay, atomic membership/outbox rollback, scoped admin bypass and billing failure guards). `TenantMembershipIntegrationTest` additionally tests GetTenantQuotas through production mTLS/JWS policy and current primary membership revocation. See [SEAT quota contracts and activation](../../tenant-service/SEAT-QUOTAS.md). External invitation/PSP/public REST/stage coverage remains separate.

TAS-360: `grpc-common-lib.verify` requires `CapabilityEvaluatorTest` and `CapabilityEvaluatorTransportTest`: current-source decisions, READ_ONLY recovery/D244, independent reasons, exact admin grant/scope/level/MFA, user PAT/MCP flags, explicit quota bypass with integrity/audit, and real mTLS/JWS negative trust paths. [Policy/source contracts and activation](../security/capabilities.md). Domain API wiring and shared stage E2E remain TAS-491/493/495/463.

TAS-334: `grpc-common-lib.verify` additionally requires `PlatformConfigurationSnapshotCacheTest`
and `VersionedCapabilitySourceIntegrationTest`: hard source deadlines without sliding,
current actor/grant revocation before cache reads, A/B source keys, version/event races,
expiry, reset/outage and mandatory notification policy. `PlatformFlagsIntegrationTest`
checks the complete secret-free snapshot against real PostgreSQL and the new production
SERVICE-only mTLS/JWS RPC, including current service authority denial with a warm cache.
After final review repeat these source-path checks. Public capabilities REST (TAS-497),
concrete domain authority wiring and shared broker/stage E2E are separate activation scopes.

TAS-324: `tenant-service.verify` требует `ProjectQuotaIntegrationTest`: production root/owner на двух PostgreSQL через mTLS/JWS, last-slot/expiry/retry/UNKNOWN, A/B, archive/hard-delete и atomic owner outbox rollback. [Контракт и активация PROJECT](../../tenant-service/PROJECT-QUOTAS.md). Project tenant storage, SEAT и membership checks повторяют затронутые schema/read paths; public REST/stage не заявлены проверенными.

TAS-352: `billing-service.verify` requires `PaymentSettlementIntegrationTest`: actual PostgreSQL,
full settlement/coverage/outbox rollback, economic deduplication, current invoice projection,
selection fences, closed creation and revoked coverage. `TenantProvisioningRpcIntegrationTest`
also covers the production settlement/Root decision chain over mTLS and separate owner databases.
See [settlement contracts and activation](../../billing-service/SETTLEMENT.md). The producer is
explicitly synthetic in this suite; external PSP/bank/public REST acceptance is separate.

TAS-320: billing-service.verify additionally requires FakePaymentProviderTest and FakePaymentProviderConcurrencyTest. These are isolated port/fake contract checks, not external PSP E2E. [Provider contract](../../billing-service/PAYMENT-PROVIDER.md).

TAS-351 adds required ProviderInboxIntegrationTest, ProviderInboxRpcIntegrationTest and InboxCipherTest: durable ACK/rollback, scoped mapping, canonical proof, real PostgreSQL/mTLS self-processing, lost Root response and private payload rotation/retention. [Scope and activation](../../billing-service/PROVIDER-INBOX.md). External PSP/public gateway E2E remains separate.
