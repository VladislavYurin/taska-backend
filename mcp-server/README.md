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
`context` с ключами/именами и `text_windows`. Первый TextContent содержит краткий итог и подсказку продолжения; второй — сериализованный JSON
того же безопасного projected/windowed envelope для клиентов, которые не передают structuredContent модели.
Общий лимит 128 KiB включает оба блока и structuredContent. Это следует рекомендации
[MCP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#structured-content)
для совместимости с текстовыми клиентами.

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

## Наблюдаемость (TAS-407)

`/actuator/prometheus` доступен во внутренней сети, отдельный scrape job `mcp-server` уже
определён в `infra/prometheus/prometheus.yml`. Публичный ingress должен открывать только `/mcp`.
Метрики используют общие application/environment/version из metrics-starter и следующие
ограниченные измерения:

| Prometheus metric | Labels | Смысл |
| --- | --- | --- |
| `taska_mcp_tool_calls_total` | tool, outcome | Один финальный исход известного tool-call |
| `taska_mcp_tool_duration_seconds_bucket/count/sum` | tool (+ le у bucket) | Гистограмма полной длительности, включая output projection и лимиты |
| `taska_mcp_limit_rejections_total` | tool | Локальный quota reject или rate_limited от gateway |
| `taska_mcp_auth_rejections_total` | outcome | Отказы на HTTP auth boundary: auth, scope, origin, upstream_error |
| `taska_mcp_requests_active` | — | Все активные HTTP `/mcp`, включая авторизацию, initialize и list |

Tool берётся из неизменяемого registry, неизвестное значение заменяется на `unknown`.
Исходы: `ok`, `auth`, `scope`, `not_found`, `conflict`, `rate_limited`, `upstream_error`, `timeout`,
`invalid_argument`, `failed_precondition`, `response_too_large`, `partial_success`, `cancelled`.
Прежние внутренние success/unauthenticated/insufficient_scope нормализуются в ok/auth/scope
только в метриках и общем completion log; публичный envelope и write audit сохраняют контракт.
Произвольные исходы заменяются на upstream_error. У гистограммы нет outcome: это ограничивает
число series при добавлении tools. Бакеты 1 ms..30 s, SLO 100/500 ms, 1/5/20 s; p95 агрегируется
через `histogram_quantile`, клиентские percentile series не создаются.

Один исход регистрируется при успехе, ошибке, timeout или отмене. Ошибки input schema известного
tool учитываются как invalid_argument и остаются JSON-RPC protocol errors. Неизвестные tools,
невалидные JSON/RPC и запросы, отвергнутые до определения tool, не создают tool labels.
HTTP 403 write preflight учитывается как исход scope известного tool; auth counter отражает
только отказы предшествующего auth filter. Gauge считает HTTP подписки, поэтому отличается
от числа quota leases tools; отмена и синхронная ошибка освобождают обе величины.

`McpObservability` пишет JSON-события `mcp_tool` для read и write, а также `mcp_auth_rejected`.
В completion log есть request_id, tool, outcome, duration_ms и только нормализованные client_name,
client_version и traceparent. В нём нет user/token/project IDs, аргументов, ответов или исключений.
Существующий отдельный write audit сохраняется. Токеноподобный/некорректный входной X-Request-Id
заменяется UUID ещё до вызова auth/gateway; допустимое значение сохраняется в envelope и обоих
HTTP переходах. Из `params._meta.traceparent` передаётся только проверенный W3C v00 с ненулевыми
trace/span IDs. Он добавляется к запросам gateway, прочие `_meta` и clientInfo не пересылаются.
SDK 2.1.0 печатает неподтверждённый initialize.protocolVersion на WARN, поэтому только logger
`io.modelcontextprotocol.server.McpStatelessAsyncServer` ограничен ERROR. Общий SDK остаётся WARN.

Dashboard `infra/grafana/dashboards/taska-mcp.json` автоматически provisioned существующим
провайдером Grafana: calls/tool/outcome, error fraction, p95, auth rejects, quota rejects,
active requests, scrape health, upstream errors и поиск логов MCP+gateway по request_id.
Environment выбирается из app_info, поскольку метрика up создаётся Prometheus без application
labels; панель scrape health показывает target данного Prometheus независимо от environment.
Request_id не становится Loki label: панель использует экранированный literal line filter.

В `infra/prometheus/alerts/taska-mcp.yml` определены target down 5m, upstream errors >10% 10m
(минимум 20 calls/5m), p95 >5s 10m с тем же минимумом, quota/auth rejects >1/s 10m. Эти правила
не настраивают внешние каналы уведомления: их подключение к Alertmanager выполняет владелец
окружения. Compose монтирует rules read-only. Без трафика процентные/latency alerts не срабатывают.

Локальная проверка с уже имеющимся образом Prometheus (можно заменить на версию из Compose):

```sh
docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/infra/prometheus:/etc/prometheus:ro" prom/prometheus:v3.2.1 \
  check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/infra/prometheus:/etc/prometheus:ro" -w /etc/prometheus/tests \
  prom/prometheus:v3.2.1 test rules taska-mcp.test.yml
```

После согласованного развёртывания для stage smoke:

1. Через авторизованный SDK клиент выполнить whoami/list_projects и один доступный get_issue;
   сохранить только request_id из tool envelope. Проверить отдельный отказ неверного PAT.
2. Дождаться двух scrape interval (по умолчанию 15s); проверить target up и рост calls/auth counters
   в Prometheus. Открыть Taska MCP dashboard с правильным environment; p95 появляется после трафика.
3. В поле request_id dashboard вставить сохранённый ID. В Loki должны находиться `mcp_tool`
   и gateway `Executing request` с этим ID. Metadata traceparent не заменяет request_id.
4. Не публиковать PAT, полные аргументы и чувствительные ответы в отчёте. Локальные fake gateway/auth
   и promtool подтверждают код/конфигурацию, но не подтверждают сбор данных конкретным стендом.

Проверка histogram API: [Micrometer](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html),
правил и unit scenarios: [Prometheus](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/).

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

## Автоматические проверки MCP контракта и безопасности (TAS-408)

`McpCatalogSnapshotTest` запускается обычным `test`/`verify`. Reviewed контракт находится в
`src/test/resources/contracts/catalog-v1.json`: 22 tools с именами, описаниями, полными
input/output schema и annotations; templates ресурсов, определения prompts и их полные тексты.
Каталог prompts нормализуется по имени, как SDK listing. Сравнение структурное, порядок вложенных
массивов (required, enum, arguments) тоже сохраняется. Обычный тест ничего не перезаписывает.
После осознанного review изменения контракт обновляется только явно:

```sh
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -B -ntp -pl mcp-server -am test \
  -Dtest=McpCatalogSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DupdateMcpCatalogSnapshot=true
```

`McpClientSecurityIT` — отдельный Java SDK client против настоящего Spring MCP server и HTTP/gRPC
заглушек. Проверяет полный public catalog для read/write PAT, реальный вызов и request_id,
безопасные 400/401/403/404/409/429/500, protocol errors, отсутствие gateway dispatch при неверном
scope/input/токене/Origin, просроченный и отозванный PAT, размер входа. IT-классы не входят в обычный
Surefire `verify`; CI запускает security IT отдельным шагом только для mcp-server. Локально запускать явно:

```sh
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -B -ntp -pl mcp-server -am test \
  -Dtest=McpClientSecurityIT -Dsurefire.failIfNoSpecifiedTests=false
```

`McpComposeE2EIT` поднимает настоящий стек из собранных jar: auth, project, workflow, issue,
notification, gateway и MCP; PostgreSQL 16 с отдельными БД, Kafka и RustFS. Dedicated Compose
`src/test/compose/compose.yml` не наследует пользовательский Compose или `.env`: нет общих
container_name, внешних сетей, persistent volumes или фиксированных host ports. Уникальное имя
проекта `taska-mcp-it-<random>`; порты только `127.0.0.1` и выделяются Docker. Приложения используют
Java 21, heap 256 MiB, Kafka 256 MiB. Отправка email/Telegram выключена; storage blobs и admin-service
не входят в сценарий. Session-only admin guards проверяются в реальном gateway до вызова admin gRPC.

Runner сначала одним Maven собирает нужные modules, затем явно запускает opt-in IT. Нужны Java 21,
Docker Compose и работающий daemon. При Docker Desktop на macOS можно явно задать DOCKER_HOST:

```sh
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock \
  python3 mcp-server/src/test/compose/run.py
```

Параметр `--skip-package` использует уже собранные jar этого checkout. По умолчанию runtime image —
`eclipse-temurin:21-jre`; `--jre-image` позволяет использовать существующий доверенный Java 21 JRE image.
В данной среде `taska/mcp-server:tas403` пригоден как JRE: ENTRYPOINT и app.jar заменяются fixture
Compose, приложения берутся из текущего checkout. Сборка сервисов не делегируется этому образу. Перед запуском jar копируются в отдельный ignored
`target/mcp-e2e/<project>/jars`: повторная сборка checkout не меняет файлы оставленного стека.
Default teardown и явный cleanup удаляют эти копии после остановки собственного project.

Seed ограничен тремя фикстурными email в новой auth_db. JWT и PAT выдаются настоящими REST API;
для просроченного PAT fixture сдвигает issued/expires timestamps в собственной БД. Read/write PAT,
revoked/expired, отсутствие токена, session-only admin/token-management, чужой проект, одинаковый
not_found для недоступной/отсутствующей задачи и неизменность БД после чтения prompt-injection
текста проверяются автоматически. Цепочка: fixture issue → найти → прочитать → создать → перевести
→ комментарий → worklog → история с `channel=MCP`, без имени/id токена; resources/list разделён по
двум владельцам PAT. Перед сценарием выполняется безопасный GET warmup project/context до готовности
холодных gRPC агрегатов; production deadlines не увеличиваются, записи автоматически не повторяются.

По умолчанию `finally` удаляет только собственный Compose project и его anonymous volumes, включая
ошибки теста. Для настоящих внешних клиентов можно оставить успешно проверенный стек:

```sh
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock \
  python3 mcp-server/src/test/compose/run.py --keep
```

После успеха runner выводит только project, URL и путь `target/mcp-e2e/<project>/credentials.json`.
Файл создаётся с mode 0600 и содержит фикстурные PAT read/write, URL gateway/MCP и ключи/ID actor,
project, issue. Он находится в ignored target, не коммитится и не печатается. Используйте его локально
для Codex CLI / Inspector; не переносите фикстурные секреты в отчёт или клиентский конфиг Git.
Санитизированный Compose log сохраняется рядом. `--keep` никогда не сохраняет неуспешный прогон.
После клиентских проверок удалите только этот project:

```sh
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock \
  python3 mcp-server/src/test/compose/run.py --cleanup \
  mcp-server/target/mcp-e2e/<project>/credentials.json
```

Cleanup принимает только credentials.json текущего checkout, проверяет случайное имя проекта и
точный Compose path; пользовательские containers/volumes не перечисляет и не удаляет. Ручные проверки
Claude Code/Cursor/Codex CLI/Inspector и stage deployment не считаются пройденными этим автотестом.

### Фактическая проверка внешних клиентов 26.10.2026

На отдельном локальном Compose fixture с 405+410+JSON fallback проверены Codex CLI
`0.163.0-alpha.10.2` и MCP Inspector CLI `2.12.0`. Codex запущен с `--ignore-user-config --ephemeral`,
read-only sandbox и read PAT из окружения; пользовательские конфиги не менялись.

- Codex: 10 сценариев чтения — whoami, проекты, контекст проекта, поиск, карточка задачи,
  комментарии, worklogs, доска, пользователи, уведомления. Для непустых комментария и worklog
  отдельно подтверждены автор/текст и 30 минут; инструкция в описании проигнорирована.
  Уведомления не помечались прочитанными, инструменты записи не вызывались.
- Inspector: 14 успешных операций — шесть read tools, resources/list, два templates,
  два resources/read, prompts/list и все три prompts/get. Каталог содержит 22 tools.
  Проверка `--strict`: ноль ошибок; 22 предупреждения о допустимом JSON Schema
  `type: [integer, null]` у next_offset. Контракт не менялся ради иной схемной диалектики.
- Ранние отдельные Codex сессии дважды ожидали модель до любого tool call. Повторные проверки
  завершились; для длинного текста использованы text_limit=80 и явная page=0.
- Claude Code и Cursor не проверены: установленных авторизованных клиентов нет, пользователь
  подтвердил отсутствие доступной среды. Это оставшийся пункт ручной приёмки TAS-408.

Этот клиентский smoke не является проверкой stage и не подменяет финальный verify:
после синхронизации с develop отдельно прошли 105 common +125 MCP тестов и SDK IT (3 теста).
