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

## Карта кода и контрактов

| Область | Владелец и точки входа | При изменении проверить |
|---|---|---|
| Внешний REST | `api-gateway`; [OpenAPI](../../api-gateway/src/main/resources/static/openapi.yml) | YAML/JSON parity, валидация, ошибки, ACL, downstream mapping |
| Внутренний RPC | [proto/v1](../../grpc-common-lib/src/main/proto/v1/) | производителей и потребителей, совместимость полей/enum, request context |
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

**Проектируется:** единый CLI, общий машинный verification manifest, автоматический выбор impact, расширенный backend stage-runner, repo-local skills и проверки документационного дрейфа. Их интерфейсы описаны в спецификации; они не считаются установленными командами.
