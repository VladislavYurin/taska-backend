## ADDED Requirements

### Requirement: Tenant-authorized project lifecycle

Сервис SHALL проверять текущий tenant, root membership, project role, lifecycle и policy перед archive/restore. OWNER/TENANT_ADMIN имеют effective ADMIN без project membership; GLOBAL_ADMIN использует проверенный административный путь с аудитом.

#### Scenario: Effective administrator changes state
- **WHEN** текущий разрешённый администратор своей компании передаёт свежую версию
- **THEN** проект меняет архивное состояние, сохраняет UUID/данные/квоту и публикует одно событие в той же транзакции

#### Scenario: Foreign or revoked actor
- **WHEN** actor чужой компании либо его текущий доступ отозван
- **THEN** команда отклоняется, состояние проекта, квота и outbox не меняются

### Requirement: Serialized versioned retries

Archive/restore SHALL сериализоваться по tenant project, требовать версию и сохранять результат по actor-bound idempotency key.

#### Scenario: Lost response and replay
- **WHEN** разрешённый actor повторяет тот же ключ, действие, проект и expected version
- **THEN** сервер возвращает исходный ответ без нового события, даже после последующего изменения версии

#### Scenario: Stale concurrent operation
- **WHEN** другая команда изменила версию перед новой archive/restore
- **THEN** stale команда получает VERSION_CONFLICT и не теряет чужое изменение

#### Scenario: Conflicting key or missing version
- **WHEN** ключ повторён с другим payload либо версия отсутствует
- **THEN** сервер возвращает IDEMPOTENCY_CONFLICT либо VERSION_CONFLICT без записи

### Requirement: Administrative integrity survives archival

Сервис SHALL сохранять защиту последнего project ADMIN и quota usage при архиве и восстановлении.

#### Scenario: Last administrator removal races archive
- **WHEN** параллельная команда пытается убрать последнего project ADMIN
- **THEN** project lock и действующая проверка последнего ADMIN сохраняют административное управление
