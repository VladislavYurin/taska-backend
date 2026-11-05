# Карта агентной разработки

Документационный слой harness создан в [TAS-452](https://jira.ozero.dev/browse/TAS-452).
Harness объединяет инструкции, доступные инструменты, проверочные команды, тестовые фикстуры и доказательства результата. Его документация рассчитана на работу из чистого checkout.

## Маршрут чтения

1. [Корневые инструкции](../../AGENTS.md) — постоянные правила.
2. [README проекта](../../README.md) — запуск, архитектура, конфигурация.
3. [Матрица проверок](verification.md) — существующие команды и выбор проверок.
4. [Спецификация harness](harness-spec.md) — полный жизненный цикл, протоколы и требования к будущей автоматизации.
5. Документы конкретного домена из таблицы ниже и текущая Jira-задача.

Не нужно загружать всю документацию для каждого исправления. Сначала определить домен и контракты, затем читать связанные материалы.

Для нового рабочего места: [setup](setup.md), [процедуры и handoff](workflows.md), [адаптер Codex](adapters/codex.md). Локальные skills версионируются вместе с кодом в `.agents/skills/`; личный контекст автора не требуется.

## Карта кода и контрактов

| Область | Владелец и точки входа | При изменении проверить |
|---|---|---|
| Внешний REST | `api-gateway`; [OpenAPI](../../api-gateway/src/main/resources/static/openapi.yml) | YAML/JSON parity, валидация, ошибки, ACL, downstream mapping |
| Capabilities и причины | [Common evaluator](../security/capabilities.md), `grpc-common-lib` | Current role/grant/ACL, exact recovery allowlist, flags, no user-token admin bypass and retained integrity/audit |
| Entitlement-проекция | [Versioned projection](../data/entitlement-projection.md), `grpc-common-lib` | Monotonic events, reconciliation, bounded cache, current authority and owner migrations |
| Внутренний RPC | [proto/v1](../../grpc-common-lib/src/main/proto/v1/) | производителей и потребителей, совместимость полей/enum, request context |
| Квоты мест | [SEAT admission](../../tenant-service/SEAT-QUOTAS.md), `tenant-service` | Membership + reservation atomicity, replay/fence/expiry, scoped admin bypass and safe quota read |
| Пользователь и доступ | `auth-service` | сессии, refresh rotation, статус пользователя, PAT scopes; [приглашения](../tas-204-invitations-and-directory.md) |
| Проекты и роли | `project-service` | ADMIN/MEMBER/VIEWER, последний ADMIN, собственность типов задач |
| Workflow | `workflow-service` | граф, типы, версии, резервирования; [привязки](../tas-436-workflow-type-bindings.md) |
| Задачи | `issue-service` | If-Match, идемпотентность, поиск/доска, история, связи, worklogs; [удаление типа](../tas-433-issue-type-deletion.md) |
| События и уведомления | `common-lib`, `kafka-lib`, `notification-service` | атомарность outbox, дедупликация, каналы; [Telegram](../tas-387-telegram-notifications.md) |
| Файлы | `storage-lib`, `auth-service`, `issue-service` | presigned URL, confirm, размер/MIME, доступ и очистка объектов |
| Администрирование | [admin-service](../../admin-service/README.md) | session-only guards, безопасное чтение, аудит, повтор outbox |
| MCP | [mcp-server](../../mcp-server/README.md) | schemas, catalog snapshot, scopes, owner isolation, REST dispatch |
| OAuth AS | `oauth-server`, `auth-service`, `api-gateway`, `mcp-server` | [AS и доверие](../tas-413-oauth-backend.md), discovery, PKCE, consent, revocation |
| Социальный вход | `oauth-server`, `auth-service`, `api-gateway` | [provider flow](../tas-424-oauth.md), [сценарии](../tas-447-social-sign-in-scenarios.md) |
| Создание компании | `tenant-service`, `billing-service`, `workflow-service` | [Durable provisioning TAS-337](../../tenant-service/PROVISIONING.md), [отмена и deadline TAS-478](../../tenant-service/CREATION-CLOSURE.md), lease/fence, remote receipts, атомарность activation/operation/outbox |
| Каталог тарифов | `billing-service` | [Каталог и границы TAS-313](../../billing-service/README.md), неизменяемость, global receipts/outbox, реальная PostgreSQL/RLS и mTLS |
| Метрики и эксплуатация | `metrics-starter`, `infra/`, Compose, `.github/workflows/` | конфигурационные тесты, секреты, health и revision каждого сервиса |

Точные имена классов, маршруты и правила брать из текущего checkout. Таблица определяет границы поиска и не заменяет код. Целевая схема БД определяется последовательностью миграций каждого сервиса.

## Доступ к внешним системам

- Jira проекта: [TAS в Ozero](https://jira.ozero.dev/projects/TAS); Confluence: [АС Taska](https://confluence.ozero.dev/pages/viewpage.action?pageId=9273617). При нескольких подключениях выбирать Ozero, сверять host перед записью.
- GitHub: [VladislavYurin/taska-backend](https://github.com/VladislavYurin/taska-backend). Проверять base PR: целевая ветка обычной разработки — `develop`.
- Swagger стенда: [публичный контракт](https://api.taska.ozero.dev/swagger-ui/index.html). Наличие URL не даёт разрешения изменять любые данные этой среды.
- Докплой и журналы читать через настроенное подключение с минимальным необходимым объёмом данных. Репозиторий не должен содержать персональные пути к credentials, реальные токены или дампы конфигурации окружения.

Если нужного подключения нет, продолжать доступную локальную работу и явно указать, какая внешняя проверка осталась недоступна. Подключение клиента к чужому одноимённому серверу не является заменой.

## Состояние реализации harness

**Есть:** Maven wrapper, CI-матрица модулей и явные IT, конфигурационные тесты, AI review, Jira sync, deployment `develop`, изолированный MCP Compose E2E, ручные backend E2E-сценарии.

**Добавляет TAS-452:** точку входа AGENTS, эту карту, матрицу проверок и спецификацию процессов/интерфейсов.

**Реализует TAS-453:** CLI `scripts/harness.py` с doctor/plan/check/docs-check, versioned verification manifest, консервативный impact по POM и event rules, контроль свежих Maven XML-отчётов, единые проверки локально и в PR CI. Команды и ограничения — в [матрице](verification.md).

**TAS-454:** [E2E/cleanup runner](e2e.md) с изолированным Compose, защищённым run-state и opt-in stage. Автоматизированы поднаборы E2E-01/08 с честным PARTIAL; остальные сценарии manual/BLOCKED.

**TAS-455:** пять repo-local skills, инструкции настройки, Codex adapter smoke и offline handoff validator. Фактическое обнаружение клиентом проверяется отдельно в новой сессии; Claude Code/Cursor не заявлены проверенными.

**TAS-456:** [регрессионные evals](evals.md) проверяют механизмы harness на собственных fixtures; отчёты и baseline остаются вне Git.
