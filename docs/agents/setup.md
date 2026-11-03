# Настройка harness Taska на новом компьютере

Этот документ рассчитан на человека, который хочет работать с Taska backend вместе с агентом без истории чата автора. Harness — файлы репозитория, проверочные команды и договорённости о доказательствах; Codex — клиент, который читает эти инструкции и пользуется вашими инструментами. Личный skill автора и его credentials не нужны.

Для агента точка входа — [AGENTS.md](../../AGENTS.md), затем [процедуры и handoff](workflows.md). Установка ничего не разрешает делать на общем стенде. B2B, merge, deployment и управление чужими данными не входят в настройку.

## 1. Что понадобится

| Компонент | Зачем / как проверить |
|---|---|
| Git и доступ к GitHub repository | `git --version`; чтение, ветки и PR в пределах ваших прав |
| Python 3.9+ | `python3 --version`; harness использует стандартную библиотеку |
| JDK 21+ | `java -version`; target release 21, PR CI использует JDK 25 |
| Maven wrapper из checkout | `./mvnw -v` после разрешения загрузок зависимостей; отдельный Maven не требуется |
| Node.js с `node --test` | automation/config tests; версию для точного CI воспроизведения брать из workflow |
| Docker Engine и Compose v2 | PostgreSQL/Testcontainers, config и local E2E; `docker compose version` |
| Codex с вашей авторизацией | desktop или CLI; [официальная установка](https://developers.openai.com/codex/quickstart) |
| Jira/Confluence Ozero, GitHub | чтение текущей задачи, критериев, PR и review; выдаются вашей учётной записи |

Без Docker доступны docs/часть static checks, но это не полная приёмка. Не меняйте Java release в POM для обхода несовместимого локального JDK. На Windows для приведённых POSIX-команд используйте согласованную Linux/WSL-среду либо адаптируйте оболочку; выполнение в native PowerShell не заявлено проверенным. [Официальный WSL guide](https://learn.chatgpt.com/docs/windows/wsl).

## 2. Получить чистый checkout

Команды ниже выполняются в выбранном вами рабочем каталоге. GitHub-авторизация настраивается штатным способом, без токена в URL clone.

```sh
git clone https://github.com/VladislavYurin/taska-backend.git
cd taska-backend
git fetch origin develop
git switch develop
git pull --ff-only origin develop
git status --short --branch
python3 scripts/harness.py --help
python3 scripts/harness.py doctor --tier static
python3 scripts/harness.py docs-check
```

Если checkout уже существует, сначала проверьте status. Не выполняйте `reset`, `clean` или переключение поверх чужой работы. Для задачи создайте worktree рядом с checkout; замените номер и slug на действительную согласованную задачу:

```sh
git fetch origin develop
git worktree add ../taska-TAS-123 -b codex/TAS-123-short-description origin/develop
cd ../taska-TAS-123
```

Номер `TAS-123` в примере — placeholder, не поручение взять эту задачу. В открываемом клиенте выберите именно новый worktree. `.env`, target и credentials из другого checkout не копируются автоматически.

## 3. Подготовить runtime

```sh
python3 scripts/harness.py doctor --tier java
python3 scripts/harness.py doctor --tier all
```

Doctor не скачивает зависимости и не запускает Maven. Первому build потребуются сеть и место для Maven/Docker cache. Исправляйте конкретный отсутствующий prerequisite; вывод doctor не подтверждает, что все tests прошли. Матрица и точные команды — [verification](verification.md).

Для обычного ручного local stack создайте `.env` из `.env.docker.example` **только если файл отсутствует**. Не выводите env на экран:

```sh
python3 -c "from pathlib import Path; p=Path('.env'); p.exists() or p.write_bytes(Path('.env.docker.example').read_bytes())"
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile infra --profile services config --quiet
```

Запуск полного приложения описан в [README](../../README.md). Backend E2E использует собственные изолированные fixtures; инструкция для них — [E2E](e2e.md). Проверяйте выбранный Docker context: локальный runner нельзя направлять на общий Docker daemon стенда.

## 4. Подключить Codex к репозиторию

В desktop-клиенте войдите под своей учётной записью, добавьте проект/папку checkout и начните новый чат в этой папке. Для CLI запустите `codex` из корня worktree. Установщик/версия клиента выбираются вашей организацией по официальной документации, а не командой скачивания `latest` из harness.

Репозиторий уже содержит `AGENTS.md` и `.agents/skills`. Не копируйте их в личный каталог и не создавайте отдельный набор правил для каждого клиента. Codex читает AGENTS от корня до рабочего каталога; более близкие инструкции и `AGENTS.override.md` могут менять итоговые правила. [Обнаружение AGENTS](https://developers.openai.com/codex/guides/agents-md).

Skills загружаются из `.agents/skills` между рабочим каталогом и корнем Git. В CLI можно вызвать skill по имени, например `$task-intake`; после обновления, которое не обнаружилось, начните новую сессию. [Официальное описание skills](https://developers.openai.com/codex/skills).

Для работы достаточно прав на выбранный checkout, ignored artifacts и собственные local fixtures. Сохраняйте sandbox и обычные подтверждения; не включайте bypass/full access ради настройки. Изменение глобальных правил и подключений — отдельное осознанное действие человека. Сначала прочитайте существующий пользовательский config, меняйте только нужные секции, не заменяйте файл целиком.

Запрос для проверки в **новой** сессии:

```text
Прочитай инструкции этого репозитория. Перечисли найденные источники AGENTS
и пять repo-local skills с их путями. Используй $docs-sync для read-only
проверки: запусти scripts/harness.py --help, doctor --tier static,
docs-check и scripts/harness/workflows.py smoke. Не изменяй файлы,
не подключай внешние сервисы. Укажи, что пока не проверено.
```

Сверьте результат с [Codex adapter smoke](adapters/codex.md). Ответ по памяти без фактического чтения/вызовов не подтверждает настройку. Проверьте, что личный `AGENTS.override.md` не скрывает проектные правила; не удаляйте чужую конфигурацию автоматически.

## 5. Настроить подключения, как в рабочем контуре Taska

Локальные проверки работают без MCP. Для полноценного цикла нужны следующие **возможности**; названия серверов могут отличаться, сначала проверяйте host и назначение:

| Подключение | Источник | Минимальная проверка без записи |
|---|---|---|
| GitHub | `VladislavYurin/taska-backend` | найти согласованный PR, прочитать head/base/checks/review threads |
| Jira Ozero | `https://jira.ozero.dev`, проект TAS | прочитать известную задачу, статус, исполнителя, связи |
| Confluence Ozero | `https://confluence.ozero.dev`, space OZ | открыть [АС Taska](https://confluence.ozero.dev/pages/viewpage.action?pageId=9273617) |
| Dokploy Ozero, при эксплуатационной задаче | endpoint предоставляет администратор | прочитать health/revisions только согласованного приложения |
| Taska API/stage, при разрешённом E2E | `https://api.taska.ozero.dev` | OpenAPI/health и capabilities своих выделенных actors |

Одноимённые корпоративные Atlassian/Kibana подключения не заменяют Ozero. Jira/Confluence web URL не является автоматически MCP URL. Попросите администратора выдать approved plugin или точный MCP endpoint/transport, список scopes, владельца и закреплённую версию bridge. Не угадывайте внутренние адреса и не устанавливайте случайный MCP package из сети.

В desktop используйте доступное подключение/плагин к сервису и штатную авторизацию. После подключения попросите агента выполнить одну read-only проверку из таблицы, сверить host и сообщить только результат. Права записи выдавайте под вашу роль; read-only доступ не нужно расширять ради проверки установки. Если bridge самописный, воспроизводимый install требует его pinned commit или image digest и инструкции владельца: этот backend repository не содержит реализацию всех MCP bridges.

Для **прямого Streamable HTTP MCP**, когда администратор дал endpoint, Codex поддерживает следующую пользовательскую конфигурацию. Это шаблон; сначала замените адрес и имя переменной локально, затем добавьте секцию в существующий `~/.codex/config.toml`:

```toml
[mcp_servers.taska_ozero]
url = "https://mcp.example.invalid/mcp"
bearer_token_env_var = "TASKA_MCP_TOKEN"
```

Значение токена сюда не вставляется. Переменную передаёт ваша ОС/secret manager в среду клиента; GUI-приложение может не наследовать переменные терминала, поэтому проверяйте подключение в самом клиенте. OAuth-сервер авторизуется отдельно через `codex mcp login SERVER_NAME`, если provider его поддерживает. `codex mcp list` проверяет конфигурацию, но только реальный read-only tool call подтверждает доступ. [MCP configuration](https://developers.openai.com/codex/mcp).

Для stdio bridge используйте утверждённый локальный executable или image digest; args из инструкции владельца. Не размещайте PAT в command line, shell history, repo config, Jira/PR или чате. Используйте env forwarding/внешнее хранилище. Не копируйте чужие `config.toml`, access.json, cookies и журналы сессий.

Harness не требует внешних skills/plugins, поэтому у него нет install-команды для `latest`. Пять локальных skills версионируются Git SHA repository. Для любого добавленного внешнего plugin сохраняйте у себя имя, owner, version, immutable ref/digest, дату smoke и выданные scopes. Если supplier не даёт immutable ref, отметьте зависимость как неприкреплённую и не объявляйте конфигурацию воспроизводимой. Service-side обновления контролируются поставщиком; pin пакета не фиксирует серверные данные.

## 6. Первый полный рабочий цикл

1. Дайте агенту Jira key, желаемый результат и явно разрешённый scope. Укажите, допустимы ли push/PR, общий stage и какие fixtures; merge/deploy оговариваются отдельно.
2. Агент выполняет `$task-intake`, проверяет свободу/зависимости и создаёт отдельную ветку от свежего develop.
3. Изменяет минимальный код/контракт, добавляет success/denial/state-preservation проверки. `$change-verification` подбирает manifest checks и бизнес-зависимости.
4. `$e2e-regression` выполняет только применимые разрешённые сценарии. Невыполненная почта/Telegram/social login остаётся gap, не PASS.
5. В PR находятся код/схемы/сценарии и санитизированное описание. Reports, credentials и state остаются в `target/` или внешнем приватном каталоге.
6. `$pr-review-followup` проверяет актуальные head/CI/review и обсуждения; после исправлений повторяет применимые checks/E2E.
7. Человек получает [handoff](workflows.md), проверяет ограничения и при необходимости разрешает merge. Ветка develop автоматически запускает deployment даже для docs.

Пример первого поручения:

```text
Выполни TAS-NNN в taska-backend. Сначала проверь статус, зависимости и PR.
Изменения только в пределах критериев задачи; B2B не включать.
Работай в отдельной ветке/worktree от origin/develop; локальные fixtures разрешены.
Подготовь отдельный PR и санитизированный handoff, merge/deployment не разрешены.
При недоступной внешней проверке продолжай независимую работу и отметь gap.
```

Для передачи другому агенту достаточно repo, key/PR, scope, SHA и handoff. Старая переписка и личные секреты не передаются.

## 7. Диагностика и обновление

| Симптом | Проверка / действие |
|---|---|
| Нет skills в новом чате | правильный worktree, `.agents/skills/*/SKILL.md`, disabled skills/global overrides; повторить client smoke |
| Doctor BLOCKED | установить/настроить конкретный runtime, Docker context или PATH; не менять project release |
| Команда дала exit 0, tests нет | проверить harness report/selector и свежий XML; обычный Maven success недостаточен |
| Jira/Confluence не доступны | host Ozero, собственная авторизация/scopes; продолжить локально, внешний статус BLOCKED |
| E2E PARTIAL/BLOCKED | прочитать reason/capability map; не обходить mailbox/квоты/actor ограничения |
| Прерванный E2E | исходный защищённый run-state и `cleanup --help`; не править ownership вручную |
| Review зелёный на старом SHA | дождаться/выполнить review текущего head, затем повторить затронутые checks |
| Другой агент не воспроизводит результат | сверить sourceHead/tree, dirty, runtime versions, actual checks и capability gaps |

После обновления repository/client/plugin повторите static doctor, docs-check, adapter smoke и один согласованный workflow. Новый client/version требует нового наблюдения обнаружения инструкций. Не переносите status PASS из старой установки.

Регрессионная оценка самого harness — следующий этап TAS-456.
