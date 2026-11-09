# Backend E2E runner — TAS-454

Entry point: `python3 scripts/harness.py e2e`. Поддерживаемая среда runner — Linux/macOS (Windows через WSL), Python 3.9+, Git, Java 21+, Docker Engine и Compose v2. Используется существующий [MCP Compose fixture](../../mcp-server/src/test/compose/compose.yml). Runner не меняет бизнес-код, shared stage, B2B или deployment policy.

## Локальный запуск

Из корня чистого checkout:

```bash
python3 scripts/harness.py doctor --tier java
python3 scripts/harness.py e2e --tier local --scenario E2E-01 --scenario E2E-08
```

Runner всегда собирает JAR текущего checkout через Maven wrapper (`package -DskipTests`), копирует их в каталог RUN, создаёт уникальный Compose project `taska-harness-<16 hex>` с динамическими loopback-портами. У каждого RUN собственные PostgreSQL tmpfs, Kafka, storage, service containers и network. Unit/IT эта сборка не заменяет: они выполняются отдельным `check`. Базовый JRE fixture — `eclipse-temurin:21-jre`; Docker получает образы обычным механизмом pull/cache. Фактические image IDs и SHA-256 JAR записываются в report.

Локальные A/M/V/O/G создаются только в собственной пустой fixture DB (как в исходном MCP E2E); дальнейшие сценарии используют публичный REST. Secrets генерируются заново и остаются в памяти/приватном временном compose-файле. Существующий `.env` не читается и не меняется. Docker cleanup удаляет только контейнеры и network с одновременно совпадающими Compose project и случайной ownership label. После успешной очистки приватный compose-файл и JAR snapshots удаляются.

`--run-id 0123456789abcdef` необязателен. Повторное использование RUN отвергается, в том числе при параллельном запуске. Для двух запусков используйте разные RUN; packaging и копирование JAR сериализуются build lock, сами стеки независимы. Активный RUN защищён lock от параллельного cleanup. Не запускайте Maven сторонним процессом в том же checkout во время packaging; параллельные агентные задачи используют отдельные worktree.

## Результаты и exit codes

Результаты находятся в ignored `target/harness-e2e/<RUN>/`: `report.json` и подписанный `state.json`, mode `0600`, каталог `0700`. Локальный `.state-key` связывает state с checkout; не переносите его в Git или другой checkout. Diagnostic при ошибке startup сохраняется приватно и не предназначен для Jira/PR. Публичный JSON не содержит токенов, персональных email, HTTP body или raw env.

Report содержит source head/tree, dirty marker, revisions до/после, scenario/check ID, фактическое число выполненных assertions, scope, cleanup и остаточные ресурсы. Для dirty checkout `sourceTree` — Git tree HEAD, **не снимок незакоммиченных файлов**; такой прогон нельзя выдавать за проверку чистого SHA. HTTP operation coverage отдельно от количества полностью пройденных сценариев.

Коды: `0` полный PASS; `1` FAIL/ошибка cleanup; `2` неверный вызов; `3` BLOCKED (предпосылка/неавтоматизированный выбранный сценарий); `4` PARTIAL. Нынешние E2E-01/08 выполняют содержательные поднаборы и возвращают PARTIAL, даже если все реализованные assertions прошли. Смена revision также PARTIAL. Это не «зелёная полная приёмка» всех backend-сценариев.

## Cleanup и восстановление

Cleanup выполняется в `finally`, включая Ctrl-C/SIGTERM. SIGKILL и выключение машины не позволяют выполнить finally; сохранённый state позволяет повторить:

```bash
python3 scripts/harness.py cleanup --run-state target/harness-e2e/0123456789abcdef/state.json
```

Команда идемпотентна. Она не исполняет argv/compose path из state и не принимает произвольные Docker project names. Подмена HMAC, пути, checkout, environment или ownership отклоняется. Для local сохраняется наблюдаемый Docker daemon ID: смена context/DOCKER_HOST на другой daemon запрещает cleanup, даже если в нём не найдено контейнеров RUN. При потере `.state-key` автоматическая очистка недоступна: оператор отдельно сопоставляет реальные Docker labels и владельца; не создавайте фиктивный state.

Для создания проекта сначала сохраняется pending intent, затем отправляется единственный POST. После transport timeout повтор записи не выполняется. Stage cleanup ищет свой projectKey и createdBy через публичный список, проверяет также уникальный creation marker из исходного POST description, сохраняет найденный ID и архивирует. Совпадения actor/projectKey недостаточно: старый проект с другим marker не принимается в registry. При явном отказе 4xx intent удаляется; после timeout/5xx сохраняется для безопасного reconcile. Если запись ещё не видна, pending intent остаётся unresolved: повторите cleanup позже. Отсутствие объекта сразу после timeout не считается доказательством отсутствия записи.

## Opt-in stage

Stage не запускается без `--tier stage --config FILE`. До запуска владелец среды выделяет пять разных ACTIVE actors A/M/V/O/G, разрешает создание и архивирование собственных проектов и принимает остаточные archived projects (публичного удаления проекта нет). Обычный JWT нужен для G; GLOBAL_ADMIN не заменяет project ADMIN. Runner не создаёт/блокирует пользователей на stage, не читает secrets из DB и не выполняет fault injection.

Конфигурация хранится **вне Git**, принадлежит пользователю и имеет mode `0600`. Пример структуры (значения заменяет владелец среды; приведённый пример не является готовым доступом):

```json
{
  "environmentId": "approved-stage-sandbox",
  "origin": "https://stage.example.invalid",
  "allowedOrigins": ["https://stage.example.invalid"],
  "allowProjectArchiveResidue": true,
  "actors": {
    "A": {"id": "11111111-1111-4111-8111-111111111111", "tokenEnv": "HARNESS_A_TOKEN"},
    "M": {"id": "22222222-2222-4222-8222-222222222222", "tokenEnv": "HARNESS_M_TOKEN"},
    "V": {"id": "33333333-3333-4333-8333-333333333333", "tokenEnv": "HARNESS_V_TOKEN"},
    "O": {"id": "44444444-4444-4444-8444-444444444444", "tokenEnv": "HARNESS_O_TOKEN"},
    "G": {"id": "55555555-5555-4555-8555-555555555555", "tokenEnv": "HARNESS_G_TOKEN"}
  },
  "revisionPaths": {
    "api-gateway": "/harness-revisions/api-gateway",
    "auth-service": "/harness-revisions/auth-service",
    "project-service": "/harness-revisions/project-service",
    "workflow-service": "/harness-revisions/workflow-service",
    "issue-service": "/harness-revisions/issue-service",
    "notification-service": "/harness-revisions/notification-service"
  }
}
```

`revisionPaths` — требуемая внешняя capability: каждый same-origin read endpoint отвечает `{"revision":"immutable-sha-or-digest"}`. **Taska не предоставляет эти example routes автоматически.** Владелец среды должен предоставить read-only наблюдение revisions (например, через свой deployment gateway); статические строки в config не являются наблюдением. Без такой интеграции stage остаётся BLOCKED. Этот PR не добавляет продуктовых endpoint или инфраструктуру стенда.

Токены безопасно загрузить в указанные env names через approved secret manager/клиент; не вводить literal tokens в shell history и не публиковать `env`. Пример запуска после настройки:

```bash
python3 scripts/harness.py e2e --tier stage --scenario E2E-08 --config "$HARNESS_STAGE_CONFIG"
python3 scripts/harness.py cleanup --run-state target/harness-e2e/0123456789abcdef/state.json --config "$HARNESS_STAGE_CONFIG"
```

HTTP redirects не следуются, origin строго HTTPS и точно совпадает с allowlist. Перед записью `/users/me` подтверждает UUID каждого actor, health и observed revisions. При cleanup origin/environment/actors должны совпасть с подписанным state; живой объект повторно проверяется по createdBy, projectKey и роли ADMIN. Архивирование оставляет объект в `remainingOwnedResources` как `archived-project` — это ожидаемый согласованный остаток, не физическое удаление.

## Карта сценариев

Источник критериев — [backend E2E](../testing/backend-e2e-scenarios.md). Реализованные поднаборы:

| ID | Actors / budget / cleanup | Автоматизировано | Не закрыто |
|---|---|---|---|
| E2E-01 | A; только чтение; cleanup не нужен | Контракт JSON/YAML доступен, meta auth, ETag/304/invalid ETag, no-store auth methods | Семантическое равенство YAML/JSON, полные словари, fault fixture неизвестных downstream enum, Swagger UI |
| E2E-08 | A/M/V/O/G; 2 проекта, 2 членства, 4 отказа создания; локальный destroy либо stage archive | Создание/дубликат/валидация, сохранность после отказа, PATCH omit/null, роли, отзыв, последний ADMIN, outsider/global-admin ACL, список/context/stats | Неактивный signup/BLOCKED membership; содержательная сверка stats/context; cross-project issue/label/link/search isolation |

Все остальные IDs из исходного документа runner распознаёт, но возвращает **BLOCKED / manual**, не молча пропускает. Список ниже generated из заголовков документа при реализации; при добавлении сценария обновлять эту карту и tests.

| ID | Ручной сценарий / нужная capability |
|---|---|
| E2E-02 | Вход, профиль и ротация сессии |
| E2E-03 | Приглашение и активация |
| E2E-04 | Самостоятельная email-регистрация |
| E2E-05 | Социальный вход и регистрация |
| E2E-06 | PAT, scope и канал действий |
| E2E-07 | Каталог пользователей |
| E2E-09 | Реестр типов, оформление и порядок |
| E2E-10 | Собственный workflow и граф |
| E2E-11 | Перенос типов между workflow и выбор workflow нового типа |
| E2E-12 | Начальный статус и удаление статуса/workflow |
| E2E-13 | Удаление непустого типа с переносом задач |
| E2E-14 | Создание задачи, идемпотентность и чтение |
| E2E-15 | Изменения задачи и оптимистичная блокировка |
| E2E-16 | Назначение, переходы и история |
| E2E-17 | Списки, поиск и статистика |
| E2E-18 | Доска, unplaced и курсор колонки |
| E2E-19 | Комментарии и счётчик |
| E2E-20 | Worklogs и суммарное время |
| E2E-21 | Связи, метки и наблюдатели |
| E2E-22 | Вложения и аватар через object storage |
| E2E-23 | Удаление задач и архив проекта |
| E2E-24 | Inbox и настройки доставки |
| E2E-25 | Подключение Telegram и webhook |
| E2E-26 | Глобальное администрирование пользователя |
| E2E-27 | Data, аудит и outbox |
| E2E-28 | Изоляция, повтор после сбоя и границы полного прогона |
| E2E-29 | Подключённые OAuth-приложения |
| E2E-30 | Повторный старт auth-service и сохранность истории миграций |
| E2E-31 | Страницы OAuth: вход, согласие, отказ и смена аккаунта |
| E2E-32 | Социальный вход в OAuth browser session |
| E2E-33 | Публичный OAuth ingress и приватные HTTPS endpoints |
| E2E-34 | Полный OAuth flow настоящего MCP Inspector |
| E2E-35 | Scopes, отзыв одного grant и изоляция OAuth владельцев |
| E2E-36 | Гонки создания и оптимистичного изменения задачи |
| E2E-37 | Idempotency replay после изменения прав |
| E2E-38 | Курсор с удалённым anchor и крайние страницы |
| E2E-39 | Конкурентные worklogs и чужой идентификатор записи |
| E2E-40 | Unicode: code points, UTF-16 и неизменность после отказа |
| E2E-41 | OAuth: отклонённый token request не расходует code |
| E2E-42 | MCP: поддельный канал, scopes и публичная история |
| E2E-43 | GLOBAL_ADMIN не заменяет проектную роль |
| E2E-44 | Квота выдачи PAT и независимость владельцев |

Email/Telegram/social позитивные пути остаются непроверенными без реально согласованных mailbox/bot/provider. Действие с отсутствующей capability не подменяется отказом API или локальным mock. Полные quota/lockout/concurrency/fault сценарии выполняются только в собственном fixture environment, не на shared stage.

## Проверка и расширение runner

```bash
python3 -B -m unittest discover -s scripts/harness -p 'test*.py'
python3 scripts/harness.py docs-check
```

Для нового сценария сохранить stable ID, объявить preconditions/actors, число и тип записей, ожидаемые success/errors, bounded polling и cleanup. Сначала реализовать write-ahead registry и отказ при missing capability, затем позитивный путь и проверку сохранности после отказа. В tests моделировать Docker/HTTP только своей fixture; дополнительно выполнить реальный local run. Не менять итоговый PARTIAL на PASS до покрытия всех пунктов исходного сценария.
