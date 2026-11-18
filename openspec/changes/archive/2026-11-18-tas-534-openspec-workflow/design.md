## Context

Есть AGENTS, карта разработки, пять Taska skills и verification manifest. OpenSpec дополняет их и сохраняет источники контрактов и полномочий.

## Decisions

- CLI 2.1.0, installer и skills закреплены на release ref; проектные skills доступны из Git.
- Context ссылается на текущие команды и ограничения Taska, а не заменяет их копией устаревающих endpoint lists.
- Бизнес-вопросы фиксируются в связанных Jira-задачах. Существующее поручение на реализацию/merge сохраняется между этапами.
- `validate --archived` выбирает другой режим: active specs/changes и archived tasks проверяются двумя отдельными командами.
- Metadata allowlist ограничен version/config/tool-marker/change-marker. Неизвестный executable сохраняет полную Java/config матрицу.
- Baseline процесса находится непосредственно в specs; инфраструктурный change явно использует skip_specs.

## Risks / Trade-offs

CI download зависит от GitHub releases; установщик проверяет checksum, версия и его source закреплены. Структурный smoke не является client discovery. Java/runtime E2E не применимы к tooling-only изменению; harness mapping проверяется self-tests, грамматика — настоящим CLI.

## Open Questions

Нет. Продуктовые решения не вводятся.
