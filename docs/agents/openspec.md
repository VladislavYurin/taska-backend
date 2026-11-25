# OpenSpec в Taska backend

OpenSpec фиксирует требования и сценарии задачи перед реализацией. Jira определяет назначение, зависимости и бизнес-решения; REST, proto и миграции остаются авторитетными исполняемыми контрактами. Проверки определяет [матрица harness](verification.md), постоянные ограничения — [AGENTS](../../AGENTS.md).

## Закреплённая версия

CLI **2.1.0** закреплён в [`.openspec-version`](../../.openspec-version). Шесть проектных skills из официального `v2.1.0`, commit `8bd4b3557b5bf1d231cd2f165b28f24e2d90a2a0`, сохранены в `.agents/skills`: propose, apply-change, update-change, explore, sync-specs и archive-change. [MIT license](../../openspec/UPSTREAM-LICENSE.md) сохраняется вместе с ними. Персональные tool profiles и credentials в Git не нужны.

Standalone CLI не требует Node.js. Установить закреплённую версию macOS/Linux можно по [официальной инструкции](https://openspec.dev/docs/installation) либо так:

```sh
curl -fsSL --retry 3 https://raw.githubusercontent.com/Fission-AI/OpenSpec/8bd4b3557b5bf1d231cd2f165b28f24e2d90a2a0/scripts/binary/install.sh -o /tmp/taska-openspec-install.sh
OPENSPEC_VERSION=2.1.0 sh /tmp/taska-openspec-install.sh
openspec --version
```

Установщик проверяет SHA-256 release asset. Для другого каталога задать `OPENSPEC_INSTALL_DIR`, затем настроить PATH своей оболочки. Обычный Maven build не устанавливает CLI и не правит shell startup files.

В чистом checkout контекст и skills уже есть; повторный init для повседневной работы не нужен. Намеренное восстановление закреплённым CLI:

```sh
OPENSPEC_TELEMETRY=0 openspec init --tools codex --scope project --profile core --no-animation
```

Перед восстановлением проверить локальные изменения и старые OpenSpec files: init умеет удалять устаревшие собственные артефакты. Обновление CLI и vendored skills — отдельное изменение с проверенным upstream ref и новой закреплённой версией, без автоматического latest.

## Процесс задачи

1. Выполнить [task-intake](../../.agents/skills/task-intake/SKILL.md), проверить свободу задачи и develop, создать отдельную ветку.
2. Проверить OpenSpec root командами list/context и создать change с Jira key.
3. До реализации оформить proposal, design, specs и tasks. Описать наблюдаемые успех, отказ, сохранность состояния, версии, повторы и конкурентность. Проза — по-русски; protocol identifiers сохраняются.
4. Реализовать задачу и авторитетные контракты, проверить потребителей, пройти current-head review и повторить затронутые E2E. Evidence остаётся вне Git.
5. Синхронизировать specs и архивировать завершённый change до merge. Archive не выдаёт разрешения на merge/deploy; действует поручение пользователя.

```sh
openspec list --json
openspec context --json
openspec new change tas-533-workflow-context-errors
openspec status --change tas-533-workflow-context-errors --json
openspec validate --all --strict --no-interactive
openspec archive tas-533-workflow-context-errors --yes
openspec validate --all --strict --no-interactive && openspec validate --archived --no-interactive
```

В Codex CLI/IDE skills вызываются как `$openspec-propose`, `$openspec-apply-change`, `$openspec-archive-change`; в desktop доступны через Skills. Наличие файлов и CLI smoke не доказывает discovery уже открытого клиента: это проверяется новой сессией по [адаптеру Codex](adapters/codex.md).

Прямое поручение реализовать и довести до merge сохраняет силу между этапами планирования и реализации. Повторное разрешение на каждый внутренний этап не требуется. Если продуктовый результат не определён действующими контрактами и принятыми требованиями, создать отдельную связанную Jira-задачу: контекст, варианты, затронутые критерии и блокирует ли вопрос работу. Независимую работу продолжать; решение не выдумывать.

OpenSpec не расширяет доступ к данным, общей среде, deployment или параллельным агентам. Секреты, приглашения, presigned URLs, raw logs и run-state не входят в specs/archive. Невыполненный внешний сценарий отмечать как пробел, а не PASS.

## Проверки процесса

```sh
OPENSPEC_TELEMETRY=0 openspec validate --all --strict --no-interactive && openspec validate --archived --no-interactive
python3 scripts/harness.py docs-check
python3 scripts/harness.py check --group harness
python3 scripts/harness/workflows.py smoke
```

PR CI устанавливает закреплённый CLI через закреплённый installer source и выполняет strict validation, включая завершённость tasks архивных changes. Harness относит известные OpenSpec metadata и Markdown к документации с self-tests процесса; неизвестный executable внутри openspec сохраняет консервативную полную Java/config матрицу.
