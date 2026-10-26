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

## Инструменты записи (TAS-405)

Все 12 инструментов требуют PAT `read,write`; handler повторно проверяет `write`, а gateway
сохраняет исходные ACL. Публичного удаления задач/проектов/комментариев/worklogs и массовых
изменений нет. Все annotations имеют `destructiveHint=false` и `readOnlyHint=false`.

| Инструмент | Ограничение / условие |
| --- | --- |
| `create_issue` | Один проект/объект, summary 1..255, description до 32768; UUID idempotency_key обязателен |
| `update_issue` | PATCH, минимум одно изменение, If-Match из аргумента или авторизованного by-key snapshot |
| `transition_issue` | Точный key/id/уникальное имя статуса собственного workflow типа; только доступный нескрытый переход |
| `assign_issue` | me/UUID/точное имя/email либо null для снятия; тот же исполнитель — без PATCH |
| `add_comment` | body 1..10000, не пробелы; без durable idempotency |
| `log_work` | spent_minutes 1..1440, существующая ISO date, comment до 2000; без durable idempotency |
| `add_label` / `remove_label` | Только существующая метка проекта, меняется только привязка; повтор — без записи |
| `link_issues` | Обе задачи разрешаются с ACL, BLOCKS/RELATES_TO/DUPLICATES; без durable idempotency |
| `watch_issue` / `unwatch_issue` | Только текущий пользователь и одна задача; повтор безопасен |
| `mark_notifications_read` | Ровно одно собственное уведомление, результат подтверждается id/readAt |

Лимиты body/comment соответствуют REST; дополнительно проверяется длина UTF-16, поскольку
Bean Validation gateway считает её, а JSON Schema maxLength считает Unicode code points.

`create_issue` использует первый тип реестра, если issue_type отсутствует; неизвестный тип
не становится TASK. Метки и неоднозначный assignee проверяются до создания. Idempotency-Key
всегда передаётся в REST: новый UUID на новое намерение, прежний UUID и те же аргументы при повторе.
Дополнительные назначения/метки не образуют общей транзакции: `partial_success` возвращает
`created_issue_key`, `completed_steps`, `remaining_steps`. Повтор перечитывает созданную задачу
и не повторяет уже установленного исполнителя/метки. Если за это время другой исполнитель уже
назначен, автоматического перезаписывания нет: завершите через `assign_issue` после согласования.
Не меняйте аргументы, сохраняя прежний idempotency_key. Durable гарантия REST относится к созданию
базовой задачи; дополнительные шаги восстанавливаются по её текущему состоянию.

Update/assign/transition всегда условные. Явная version не заменяется свежей прочитанной версией.
409 вызывает повторное авторизованное чтение и возвращает current_version/current_status,
без повторной записи. Уже достигнутый статус возвращается без нового события.

HTTP client не выполняет автоматических повторов (включая Reactor Netty retry on reset).
После таймаута/обрыва/5xx результат записи может быть неизвестен: `WRITE_OUTCOME_UNKNOWN`
требует перечитать объект/комментарии/worklogs/связи и уточнить намерение при неоднозначности.
Ошибка output schema или превышение result cap после записи также запрещает повтор вслепую.
Для create безопасен только прежний idempotency_key; для комментария, worklog и связи такой
гарантии REST нет. Отмена запроса не обещает отката принятой операции.

`ToolAuditTarget` содержит только валидированный ключ/UUID, отдельно от пользовательских имён
и DTO context. `ToolInvocation` сохраняет совместимый четырёхаргументный constructor.
Write API принимает только `GatewayMutation` и `GatewayWriteOptions` (version/UUID key),
без произвольных URL, методов или заголовков. Все используемые REST routes уже имеют
`x-pat-scope: write`; это закреплено тестом по исходному OpenAPI.

Write snapshot обновляется явно, с сохранением порядка существующих свойств:

```sh
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -pl mcp-server -am test \
  -Dtest=ReadSchemaExportTest,WriteSchemaExportTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DupdateMcpReadSchemas=true -DupdateMcpWriteSchemas=true
```

Проверены все 12 handlers через stateful HTTP stub и реальный Java SDK сценарий с gRPC auth stub:
создание/повтор, переход, конфликт версии, комментарий и worklog. Доменная история/уведомления
на развёрнутом стенде и сценарий внешнего агента здесь не подтверждаются — это TAS-408.


## Ресурсы и промпты (TAS-410)

`resources/templates/list` публикует `taska://issues/{key}` и `taska://projects/{key}`.
`resources/read` возвращает Markdown карточки с последними 20 событиями истории либо контекста
проекта (реестр типов, workflow каждого типа, метки, участники). Это те же `get_issue`/`get_project`
через общий ToolExecutor: исходные ACL gateway, PAT read, квоты, таймауты, текстовые окна и лимиты
действуют без отдельного обходного пути. Лимит 128 KiB повторно проверяется после Markdown-экранирования
по сериализованному `ReadResourceResult`. При усечении описание продолжается через поддерживаемый
`get_issue text_offset`, комментарии/worklogs — через соответствующие инструменты со страницей.
Недоступный и отсутствующий ресурс дают одинаковый стандартный resource-not-found, без сведений
о чужом объекте. Произвольные URL, query/fragment и пути файлов не принимаются.

`resources/list` выполняет один `search_issues page=0,page_size=20` от текущего пользователя:
не более 20 доступных новых задач в порядке REST `created_at desc,id desc`. Кэширования между
пользователями и обогащения запросом на каждую строку нет. Cursor не поддерживается; для дальнейших
страниц служит `search_issues`. Статический список SDK общий для сервера, поэтому узкая обёртка
публичного `McpStatelessServerTransport.setMcpHandler` переопределяет только `resources/list`.
Остальные методы, уведомления, версии протокола и завершение работы остаются у SDK; штатный
WebFlux router не заменяется. Реальный SDK integration test закрепляет wiring этой обёртки.

Промпты на русском: `triage_my_issues` (необязательный project_key), `standup` (project_key,
period: today/yesterday/7d/14d/30d, UTC) и `decompose_issue` (обязательный issue_key).
Они читают сначала; декомпозиция требует явного подтверждения перед вызовом `create_issue`.
Промпт стендапа честно предупреждает, что REST search не фильтрует период, а история ограничена.
Сам `prompts/get` не вызывает бизнес-API и не меняет данные. Аргументы строго проверяются;
пользовательские описания, имена и история объявлены недоверенными данными. Markdown описания
изолированы fence, который длиннее любой последовательности backtick внутри текста.

Для MCP 2025-11-25 используется HTTP `Cache-Control: private, no-store`; выдуманных `ttlMs`,
`cacheScope`, подписок и уведомлений listChanged нет. Снимок двух templates, трёх определений
и полных русских prompt templates — `schemas/resources-prompts.json`. Изменение обновляется явно:

```sh
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -pl mcp-server -am test \
  -Dtest=ResourcePromptSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DupdateMcpResourcePromptSnapshot=true
```

Проверены HTTP/gRPC stubs и настоящий Java SDK: discovery, Markdown, три промпта, два PAT
с разными списками задач, отсутствие кэша между ними, одинаковая ошибка чужого/отсутствующего
ресурса и HTTP cache headers. Inspector, внешний агент и развёрнутый стенд проверяются в TAS-408;
этот локальный прогон не подтверждает их работоспособность.
