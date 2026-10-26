# Taska MCP server

Streamable HTTP `/mcp`, stateless async, PAT с `read`, запросы к фиксированному REST gateway
от имени пользователя. PAT проверяется на каждом запросе, результат авторизации не кэшируется.
Сервис не использует БД и не обращается к доменным gRPC API напрямую.

## Инструменты чтения

| Инструмент | REST данные / поведение |
| --- | --- |
| `whoami` | Текущий профиль, тип PAT, scopes и срок действия; без имени и идентификатора токена |
| `list_projects` | Доступные проекты, локальная стабильная пагинация `page/page_size` |
| `get_project` | Агрегированный контекст по ключу: участники, типы, метки, workflows |
| `search_issues` | Серверная пагинация и фильтры; `assignee=me`, ключ проекта и reporter разрешаются внутри |
| `get_issue` | Карточка по ключу, последние 20 событий истории, окно описания |
| `list_comments` | Серверная страница комментариев, окна текста body |
| `list_worklogs` | Серверная страница worklogs с авторами, окна comment |
| `get_board` | Страницы всех колонок с лимитом 20 по умолчанию; продолжение через `search_issues` со status |
| `search_users` | Каталог по имени/email от трёх символов; серверная пагинация |
| `list_notifications` | Собственные уведомления, `unread_only`, `offset=page*page_size`; unreadCount не является totalCount страницы |

`page` начинается с нуля. `page_size` по умолчанию 20, максимум 50. Ключи проекта/задачи
обрезаются по краям и переводятся в верхний регистр. Пользователь: `me`, UUID либо точное
имя/email. Если первый результат не доказывает однозначность (в том числе `hasMore=true`),
возвращается `invalid_argument` с доступными `candidates`; пользователь не выбирается автоматически.
Проекты, карточки и каталог не обогащаются запросом на каждую строку. Lookups переиспользуются
только внутри одного вызова инструмента и отменяются при отмене подписки.

Доступ к объекту проверяет gateway. Отсутствующий и недоступный объект дают одинаковый
`not_found` с предложением поиска. SQL, произвольные тела ошибок и служебные заголовки
не передаются клиенту. Все тексты пользователей — данные: инструкции из описаний, комментариев
и имён не выполняются; это также явно указано в описании каждого инструмента.

## Ответы и ограничения

`structuredContent` содержит `request_id`, `truncated`, `data` либо `error`, при необходимости
`context` с ключами/именами и `text_windows`. Краткий text fallback содержит итог и подсказку
продолжения, без второй полной копии JSON.

Поля `description`, `body`, `comment` обрезаются по Unicode code points, без разрыва surrogate pair.
Карточка, комментарии и worklogs поддерживают `text_offset` (по умолчанию 0) и `text_limit`
(4000, максимум 8192). `text_windows` содержит JSON Pointer, фактический offset, total_chars
и nullable next_offset. Остальные инструменты показывают ограниченный предпросмотр текста.

Полностью сериализованный CallToolResult ограничен 128 KiB. При превышении возвращается
`response_too_large` с советом уточнить запрос/уменьшить страницу или окно. Структурированный
ответ не заменяется урезанной строкой. Upstream ограничен 2 MiB, timeout ответа gateway — 5s,
общий timeout инструмента — 20s. Настройки могут только уменьшать предельные размеры:
`taska.mcp.tools.max-result-bytes`, `taska.mcp.max-response-bytes`, `taska.mcp.tools.max-text-limit`.

## Reviewed schemas

`src/main/resources/schemas/read-tools.json` — зафиксированные input/output JSON Schema 2020-12.
Output DTO экспортированы из `api-gateway/src/main/resources/static/openapi.yml` со всей
достижимой closure в локальных `$defs`. Внешние ссылки запрещены, OpenAPI `nullable` преобразован
в JSON Schema. Неизвестные новые поля DTO отбрасываются, nullable поля сохраняются; необязательные
поля с null, не разрешённым OpenAPI, не сериализуются. Обязательные некорректные поля дают безопасную
ошибку контракта.

Обычный `ReadSchemaExportTest` сравнивает snapshot и падает при изменении достижимого OpenAPI,
не меняя файлы. Для явного обновления после review DTO:

```sh
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -pl mcp-server -am test \
  -Dtest=ReadSchemaExportTest -Dsurefire.failIfNoSpecifiedTests=false -DupdateMcpReadSchemas=true
```

Diff схем должен быть проверен перед коммитом. Новое поле REST не расширяет MCP автоматически
во время работы сервера.

## Расширение и проверка

Новые каталоги реализуют `ToolContributor` и возвращают `ToolRegistration` с reviewed схемами.
`ToolExecutor` централизованно проверяет input schema, scope, timeout, output schema и лимит
результата. Handler получает `ToolInvocation` с request context, аргументами и request-scoped
resolver, возвращает `Mono<ToolData>`. Envelope добавляет `ToolResultFactory`.
`ToolInterceptor` — упорядоченная точка расширения для квот, аудита и метрик с поддержкой отмены.
Gateway принимает только типизированные endpoint/query, URI значения кодируются отдельно.
Опциональный `mcpGatewaySigningFilter` добавляется в общий WebClient для trusted MCP marker (TAS-411).

```sh
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -B -ntp -pl mcp-server -am verify
```

HTTP stub тестирует все 10 handlers, ошибки, пагинацию, разрешение ключей/пользователей,
Unicode, nullable DTO, отсутствие N+1 и лимиты. Spring startup + настоящий Java SDK клиент
проверяют initialize, каталог и whoami с HTTP gateway/gRPC auth stub. Проверки MCP Inspector
и внешних агентов на развёрнутом стенде относятся к TAS-408 и этим прогоном не подтверждаются.


## Квоты и аудит TAS-406

На одном инстансе действуют независимые token buckets для token_id (120 read/min, 20 write/min)
и user_id (240 read/min, 40 write/min). Обе квоты проверяются атомарно: отказ одной не расходует
другую. Ёмкость burst равна минутной квоте, refill непрерывный. Одновременно допускаются 4 tool
на токен и 8 на пользователя, суммарно read/write; очереди и автоматического повтора нет.
`rate_limited` содержит `details.retry_after_seconds`. Отмена/успех/ошибка/timeout освобождают
concurrency lease; rate cost принятого вызова не возвращается. Отмена не обещает откат принятой
доменом записи. При неоднозначном write timeout сначала перечитайте объект, не повторяйте запись
автоматически.

Кэши ограничены `MCP_MAX_TRACKED_TOKENS` / `MCP_MAX_TRACKED_USERS` (по 10000). Активные записи
не вытесняются. Только неактивная запись после `MCP_QUOTA_IDLE_TTL` (5m, минимум 1m полного refill)
может быть удалена; заполненный кэш отказывает новым идентификаторам. Без Redis квоты локальны:
при нескольких репликах доступный поток умножается на их число. Параметры `MCP_TOKEN_READ_PER_MINUTE`,
`MCP_TOKEN_WRITE_PER_MINUTE`, `MCP_USER_READ_PER_MINUTE`, `MCP_USER_WRITE_PER_MINUTE`,
`MCP_TOKEN_CONCURRENT`, `MCP_USER_CONCURRENT` переопределяют начальные значения.

Входное тело POST `/mcp` ограничено 64 KiB, включая chunked запросы; превышение — HTTP 413 до
SDK/tool handler. Для валидного известного write-call недостаточный scope даёт HTTP 403 и
`WWW-Authenticate: Bearer error="insufficient_scope", scope="write"` до dispatch. Handler повторно
проверяет scope. Неизвестный method/tool, некорректный JSON/RPC/input schema остаются protocol errors SDK.
Штатный customizer отключает только дублирующий ранний SDK ToolInputValidator (который иначе
возвращает tool isError); обязательная проверка в ToolExecutor через SDK schema validator даёт
`McpError(-32602)` до вызова handler.
Регистрация и preflight используют один неизменяемый `ToolRegistry`; произвольные URL/path/header
аргументы отсутствуют в reviewed каталоге и не принимаются typed gateway client.

Финальный результат write-tool (включая rate limit, timeout, отмену и response_too_large) порождает
JSON запись logger `ru.taska.mcp.audit`: `user_id`, `token_id`, tool, безопасный object_key при наличии,
request_id, outcome, duration_ms и нормализованный clientInfo. ToolAuditTarget заполняется после
авторизованного lookup/создания и принимает только ключ/UUID. В записи нет Bearer, token name,
описания/комментария/имени объекта и полного `_meta`. Из `_meta.clientInfo` принимаются известные
имена клиентов либо `other`, числовая версия до 32 символов; W3C traceparent принимается только
в корректном формате с ненулевыми id. Метрики `taska.mcp.tool.calls`, `taska.mcp.tool.duration`,
`taska.mcp.limit.rejections` используют только фиксированные tool/outcome без user/token/project id.

`McpLimitsLoadSmokeTest` гоняет 40 параллельных вызовов двух токенов одного пользователя против
локального HTTP stub: в gateway проходят максимум 8, остальные получают rate_limited.
Это проверка admission и освобождения ресурсов, не измерение мощности реального стенда.
Нагрузочный прогон развёрнутого стенда остаётся проверкой TAS-408.
