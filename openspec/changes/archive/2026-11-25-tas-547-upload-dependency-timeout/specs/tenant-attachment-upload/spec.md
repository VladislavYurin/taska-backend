# Spec Delta

## ADDED Requirements

### Requirement: Dependency timeout сохраняет управляемый отказ и исходную операцию

В trusted-режиме upload-url система SHALL сохранять действующие dependency deadlines 5s и возвращать при временной недоступности HTTP 503 с code=DEPENDENCY_UNAVAILABLE. Timeout SHALL сохранять intent и подтверждённое состояние учёта; повтор исходного ключа SHALL не создавать вторую reservation или повторный расход. Legacy-профиль SHALL сохранять прежний формат ошибок.

#### Scenario: Policy не отвечает до и после поиска reservation
- **WHEN** текущий actor вызывает upload-url, а policy не завершает начальную проверку, admission либо replay в пределах deadline
- **THEN** ответ содержит управляемый отказ без внутренних exception text, нового расхода или потери сохранённого intent/reservation

#### Scenario: Billing не отвечает при admission
- **WHEN** billing не возвращает текущие условия до deadline
- **THEN** частичный root admission откатывается, исходный upload intent сохраняется, а повтор того же ключа после восстановления резервирует bytes один раз

#### Scenario: Owner не отвечает после принятия
- **WHEN** owner не завершает проверку proof до deadline
- **THEN** reservation и расход сохраняются до положительного proof; timeout не выполняет компенсацию или повторный COMMIT вслепую

#### Scenario: Успех и бизнес-отказы сохраняют контракт
- **WHEN** зависимости доступны либо текущий actor получает ACL/quota refusal
- **THEN** signed PUT/confirm завершается с одним COMMIT либо прежний отказ сохраняет своё значение и состояние; чужой tenant не получает доступ
