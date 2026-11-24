## Purpose

Локальный backend runner проверяет совместимость выбранного сценария с собственной fixture до побочных действий и сохраняет честный результат приёмки.

## ADDED Requirements

### Requirement: Несовместимая legacy запись блокируется до запуска
Local runner MUST возвращать BLOCKED для E2E-08 в текущей legacy fixture, которая не предоставляет verified tenant для обязательного outbox envelope v2. Отказ MUST предшествовать provision и HTTP-записям; tenant не синтезируется.

#### Scenario: Выбран только несовместимый сценарий
- **WHEN** запрошен local E2E-08
- **THEN** runner возвращает exit code 3, scenario result BLOCKED, testCount 0, отсутствие HTTP operations, pendingWrites и ресурсов, cleanup PASS

### Requirement: Независимые сценарии и среды сохраняются
Preflight MUST сохранять запуск local E2E-01 в смешанном наборе и не менять opt-in stage маршрутизацию E2E-08. Native project проверка остаётся отдельной.

#### Scenario: Смешанный локальный набор
- **WHEN** запрошены local E2E-01 и E2E-08
- **THEN** выполняются только чтения E2E-01, E2E-08 остаётся BLOCKED и итоговый код 3 не подменяется PASS/PARTIAL

#### Scenario: Согласованный stage
- **WHEN** E2E-08 запрошен в stage с допустимой конфигурацией
- **THEN** сохраняются существующие preflight actors/revisions/ownership и вызов stage-сценария; local ограничение на него не переносится

### Requirement: Native гарантии не ослабляются
Production RPC, RLS, транзакции и outbox MUST сохранять текущие проверки. Native создание и отказы проверяются в собственном стеке с mTLS/JWS и tenant provenance.

#### Scenario: Native запись и отказ
- **WHEN** выполняется отдельный native project runner
- **THEN** успешное создание сохраняет проверенную tenant-область, а отказ не меняет доменное состояние; результат отражает реальный scope проверки
