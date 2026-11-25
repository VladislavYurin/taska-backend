## Purpose

Сохранять независимые причины ограничений компании, проверять текущие полномочия и версии команд и применять эти причины к доступу и новым автосписаниям без изменения оплаченного периода.

## ADDED Requirements

### Requirement: Independent versioned tenant restrictions

Сервис SHALL хранить причины owner pause и platform block независимо и SHALL изменять только причину, которой управляет текущий actor.

#### Scenario: Owner pauses and platform blocks
- **WHEN** owner устанавливает паузу, а проверенный администратор платформы устанавливает block
- **THEN** действуют обе причины; снятие platform block не снимает owner pause и не меняет оплату

#### Scenario: Forbidden or stale command
- **WHEN** чужой, отозванный actor, ordinary tenant admin либо stale version пытается изменить причину
- **THEN** команда отклоняется без изменения restrictions/version/audit success/outbox

#### Scenario: Replay after another change
- **WHEN** текущая разрешённая authority повторяет ключ с тем же payload
- **THEN** возвращается frozen ответ без повторного эффекта; другой payload под тем же ключом даёт IDEMPOTENCY_CONFLICT

### Requirement: Current restrictions control access

Capabilities SHALL объединять текущие root restrictions и коммерческий entitlement; pause/block SHALL закрывать новые записи, а READ_WHILE_RESTRICTED SHALL определять content read ordinary участников.

#### Scenario: Content read disabled under restriction
- **WHEN** компания paused или blocked и READ_WHILE_RESTRICTED=false
- **THEN** ordinary content read отклоняется с причиной, но разрешённое управление состоянием и финансовое чтение остаются доступны

#### Scenario: One reason is cleared
- **WHEN** снята только одна причина
- **THEN** остальные ограничения и billing expiry продолжают определять доступ

### Requirement: Financial state is independent

Pause/resume SHALL NOT списывать деньги, продлевать покрытие или снимать platform block; новые autopay admissions SHALL проверять root ограничения, а принятые операции SHALL сверяться.

#### Scenario: Resume after paid period expiry
- **WHEN** owner снимает свою паузу после expiry
- **THEN** период не продлевается, новый платёж не создаётся и ограничение оплаты сохраняется

#### Scenario: Pause before a new autopay attempt
- **WHEN** company pause/block установлена перед новым dispatch
- **THEN** новый внешний charge не допускается, а существующая accepted/UNKNOWN попытка остаётся в reconciliation

### Requirement: Versioned command contracts

REST-команды pauseTenant/resumeTenant/setPlatformBlock и одноимённые RPC SHALL проверять tenant target, UUID ключ повтора и ожидаемую версию; pause и platform block SHALL требовать подтверждение. Публичные ответы SHALL исключать свободный административный reason.

#### Scenario: Missing or stale REST version
- **WHEN** авторизованная команда не передаёт If-Match либо передаёт устаревшую версию
- **THEN** ответ имеет соответственно 428 либо 412 без изменения tenant state; другой payload под прежним ключом даёт 409

### Requirement: Fresh primary restriction authority

MembershipSnapshot, MembershipValidationBatch и TenantPaymentState SHALL возвращать текущие independent restrictions и положительную canonical restriction_version. Потребители доступа и новых autopay admissions SHALL отклонять неполный или несовместимый primary ответ вместо разрешения по старому пустому набору причин.

#### Scenario: Old or invalid root response
- **WHEN** обязательная версия отсутствует, причина неизвестна либо membership snapshots не согласованы с tenant и batch
- **THEN** потребитель возвращает UNAVAILABLE и не выполняет разрешаемую запись или новое списание
