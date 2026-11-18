## Why

[TAS-533](https://jira.ozero.dev/browse/TAS-533): контекст проекта сейчас одинаково пропускает workflow при NOT_FOUND и при недоступности, дедлайне или отказе доступа. Клиент получает ложное впечатление, что у типа нет workflow.

## What Changes

- Сохранить пропуск только для NOT_FOUND.
- Передавать прочие ошибки из workflow-service вызывающему getProjectContext.
- Обновить регрессии для частичного и полного отказа, сохранить успех и отсутствие workflow.

Выбран вариант 1, прямо разрешённый постановкой: без нового REST/proto поля. Несколько успешных workflow не скрывают отказ чтения другого типа.

## Capabilities

### New Capabilities

- `project-context-workflows`: различение отсутствующего и непрочитанного workflow в контексте проекта.

### Modified Capabilities

- Нет.

## Impact

project-service, его unit/transport проверки и сценарии контекста. REST/proto/БД не меняются; gateway сохраняет существующий механизм ошибки с request ID. Зависимость tooling: TAS-534, PR #332 должен быть влит первым.
