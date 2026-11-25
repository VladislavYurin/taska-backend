# project-context-workflows Specification

## Purpose
Различать отсутствующий workflow и отказ его чтения, сохраняя достоверность контекста проекта и исходный статус ошибки.

## Requirements

### Requirement: Отличать отсутствие workflow от ошибки чтения

Контекст проекта SHALL пропускать workflow типа только при gRPC NOT_FOUND и SHALL передавать любую другую ошибку чтения вызывающему клиенту.

#### Scenario: У типа действительно нет workflow

- **WHEN** workflow-service возвращает NOT_FOUND для одного типа и корректный workflow для другого
- **THEN** контекст успешно содержит доступный workflow и сохраняет метаданные обоих типов

#### Scenario: Workflow одного типа недоступен

- **WHEN** чтение workflow одного типа завершается UNAVAILABLE, DEADLINE_EXCEEDED, PERMISSION_DENIED, INTERNAL или FAILED_PRECONDITION
- **THEN** чтение контекста завершается исходной ошибкой даже при успешном чтении другого типа

#### Scenario: Ни у одного типа нет workflow

- **WHEN** все workflow завершаются NOT_FOUND
- **THEN** контекст успешно возвращает пустой список workflow и доступные проектные метаданные

#### Scenario: Чтение всех workflow завершается ошибкой

- **WHEN** все workflow недоступны из-за UNAVAILABLE или DEADLINE_EXCEEDED
- **THEN** контекст возвращает ошибку и не маскирует её пустым списком

### Requirement: Ошибка чтения не изменяет проект

Операция чтения контекста SHALL сохранять проект, участников, настройки и outbox при успехе, NOT_FOUND и отказе workflow.

#### Scenario: Ошибка после чтения метаданных

- **WHEN** workflow завершается ошибкой после получения доступных метаданных проекта
- **THEN** операция не записывает проектные данные, настройки или outbox
