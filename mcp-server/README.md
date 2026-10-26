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
