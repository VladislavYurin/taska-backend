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

`check --module` запускает `clean verify` и специальные IT из manifest с зависимостями. `--check` выбирает конкретный стабильный check ID, зависимый verify выполняется автоматически. Можно повторять `--check`. `--all` выполняет все 14 модулей и integration/config/harness/docs проверки последовательно. Требуются все prerequisites выбранного набора; нет скрытой установки инструментов или автоматического изменения `.env`.

Существующие groups: `java`, `config`, `harness`, `docs`. CLI не выполняет stage E2E и cleanup: они относятся к TAS-454. Отчёт JSON выводится в stdout, прогресс ID/status — в stderr. Сохранять stdout можно в ignored `target/` или внешний каталог; credentials в аргументы не передавать. CLI не создаёт постоянных raw logs; диагностика ограничена и санитизируется. Реальные subprocesses могут создавать свои build outputs и Maven cache.

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
| Workflow и привязки типов | `workflow-service`, `project-service`, `issue-service`, gateway при изменении REST | reservation/binding IT из списка ниже; версии, повторы, перенос/восстановление |
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

Источник: [verification.json](../../scripts/harness/verification.json). [PR CI](../../.github/workflows/pr-check.yml) получает все 14 модулей из manifest и вызывает тот же `check --module`. `storage-lib` и `metrics-starter` теперь имеют отдельные matrix jobs. Для `grpc-common-lib`, `kafka-lib`, `metrics-starter` явно указано отсутствие default Surefire test sources; появление таких тестов требует обновить ожидания manifest. CI проверяет документацию и Python/Node-тесты harness отдельной job.

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
