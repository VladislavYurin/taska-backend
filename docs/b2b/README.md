# Taska B2B: готовая системная аналитика

Ревизия **SA-2026-11-03**. Базовый код `af7ab57ead44304b89bbd47dabeeba45b761ce16` из актуального `origin/develop`. Business decisions D01–D253 — [единая страница Confluence](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880). **Текущая реализация, целевой дизайн и результат deployment разделены:** этот пакет выполняет аналитику, не внедряет B2B и не включает приём денег.

Сначала открыть свою техническую карточку и только указанные в ней разделы. [Порядок разработки](development-plan.md) и [машиночитаемая карта](development-map.json) содержат 127 задач на код/тесты/конфигурацию, 28 контейнеров результата, 8 выполненных аналитических карточек и одну исключённую функцию. TAS-303 — выполненная инвентаризация данных, не первая задача на код. Первые независимые реализации: TAS-306 (trusted context) и TAS-320 (PaymentProvider port/fake).

## Навигация по артефактам

| Аналитический результат | Документ / точные данные | Карточки |
| --- | --- | --- |
| Все фактические таблицы, колонки, PK/FK/unique/index/default/nullability | [data-inventory.md](data-inventory.md), [tables.json](inventory/tables.json) | TAS-303 |
| Target-модель, новые таблицы, составные ссылки, RLS, cleanup | [target-data-model.md](target-data-model.md), [target-model.json](schemas/target-model.json) | TAS-303/470/471 |
| Каждая таблица → исполнитель схемы и потребители | [implementation-ownership.md](implementation-ownership.md), [JSON](implementation-ownership.json) | 120 table dispositions |
| REST/RPC/MCP и дополнительные HTTP пути с исходниками | [api-inventory.md](api-inventory.md), [api-operations.json](inventory/api-operations.json) | TAS-468 |
| Роли, каналы, scope, operation policy, угрозы | [access-control.md](access-control.md), [access-matrix.json](inventory/access-matrix.json) | TAS-304 |
| Jobs, events, caches, native queries, files и recovery | [async-and-files.md](async-and-files.md), [async-paths.json](inventory/async-paths.json) | TAS-469 |
| Typed REST/gRPC, headers, sessions, errors, idempotency | [contracts/README.md](contracts/README.md), [b2b-openapi.yaml](contracts/b2b-openapi.yaml), [b2b.proto](contracts/b2b.proto) | TAS-305/470/471 |
| Реализуемые backend web-сессии и граница включения | [auth-sessions-implementation.md](auth-sessions-implementation.md) | TAS-315 |
| Транзакции, state machines, quota/billing/refund/delete algorithms | [state-machines.md](state-machines.md), L01–L12 | TAS-268 + реализации |
| Принятые minor решения | [technical-decisions.md](technical-decisions.md), T01–T27 | Все реализации |
| Cutover/restore/operator/config/release/retention algorithms | [operations-design.md](operations-design.md), R01–R06 | TAS-371/374/376/379/481/520 |
| Выбранный Stripe, исходы chargeback, сроки возврата | [commercial-decisions.md](commercial-decisions.md), C01–C06 | Реализации TAS-518/519/520 |
| Сроки хранения, legal holds и документы после удаления | [retention-policy.md](retention-policy.md), [JSON](schemas/retention-policy.json) | TAS-520/482/472 |
| Acceptance vectors для будущих продуктовых тестов | [scenarios/acceptance.json](scenarios/acceptance.json), 42 случая | TAS-377/378 + локальные тесты |
| AI-снимок всех согласованных бизнес-правил | [ai-business-spec.md](ai-business-spec.md) | B01–B25 / D01–D253 |

## Как проверяется полнота

Текущая схема реконструирована в новой изолированной PostgreSQL: 7 сервисов, 66 SQL-файлов, 55 прикладных таблиц, 526 колонок, 188 constraints, 186 indexes, 33 Java `@Table` models. Ещё 14 служебных таблиц Liquibase описаны как контракт движка, без ложного утверждения об их runtime наблюдении. Target включает преобразование всех существующих таблиц и полный каталог новых; точные counts генерируются из JSON и отмечены в target документе.

API inventory содержит 111 static REST, 148 RPC, 22 MCP, 15 дополнительных controllers и 11 protocol/view handlers — 307 записей, пересечения HTTP explicitly отмечены, это не 307 уникальных публичных endpoints. Target OpenAPI/proto — отдельный additive blueprint, не замена опубликованного static contract. Async inventory содержит 438 source anchors/131 файл, 93 действия матрицы и 12 проектируемых workers; текущие gaps явно отделены от design.

```sh
python3 docs/b2b/scripts/verify_data_inventory.py
python3 docs/b2b/scripts/verify_data_inventory.py --postgres --target-postgres
python3 scripts/verify_async_inventory.py
python3 docs/b2b/inventory/generate_api_inventory.py --check
python3 docs/b2b/contracts/validate_contracts.py
python3 scripts/verify_retention_policy.py
python3 scripts/harness.py docs-check
```

Для data replay нужен локальный Docker image `postgres:16-alpine`; скрипт создаёт собственный контейнер `--network none`, без host ports/persistent volumes, и удаляет его в finally. Для контрактов нужны PyYAML, jsonschema/openapi-spec-validator; точная команда protoc и requirements — в [contracts/README.md](contracts/README.md). Результат проверки схемы не доказывает реализацию бизнес-логики. Product E2E, внешний PSP sandbox, миграция общей среды и deployment этим пакетом не выполнялись.

## Статус решений и передача разработчикам

Завершены TAS-303/304/305/468/469/470/471 и агрегирующая TAS-268. TAS-518/519/520 теперь технические реализации готовых case workflow, readiness configuration и retention engine. D250–D253 закрывают пакет вопросов: Stripe выбран, live подключение отложено; окончательный partial chargeback снимает связанное покрытие; возврат отправляется в течение 5 рабочих дней, исходящая комиссия Taska; ES_V1 задаёт retention и документы после удаления. Значения реального merchant/продавца/календаря оператор вносит при подключении, USD не исключён. Разработчику не требуется новое бизнес-решение.

В каждом coding ticket должны быть конкретный результат, способ реализации, acceptance и непосредственные inputs. Контейнер не назначается как один большой PR. Старые historical документы и старые SHA не заменяют этот пакет; при изменении бизнеса синхронно обновить canonical Confluence, target schema/DTO, state algorithm, acceptance vectors и затронутые Jira карточки.
