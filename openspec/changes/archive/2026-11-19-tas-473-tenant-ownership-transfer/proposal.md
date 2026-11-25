# Proposal

## Why

TAS-473 свободна, назначена ksryzhkov и переведена в работу в активном Спринте 13 через родителя TAS-273. База — develop a0499412; TAS-312/319/323/354 завершены. Реализованные owner_version, membership locks и billing bindings пока не предоставляют публичную команду передачи владельца. Изменение не слито и не развёрнуто.

## What Changes

- Реализовать transferTenantOwnership по принятому blueprint: explicit confirmed, canonical Idempotency-Key и If-Match; дополнительного OTP и согласия получателя нет.
- Обычный owner передаёт только ACTIVE оплаченный tenant при включённом флаге; owner pause разрешена, остальные ограничения и FREE запрещены. Актуальный GLOBAL_ADMIN/MFA сохраняет B02 business bypass, но не целостность и tenant boundary.
- Под Root lock атомарно заменить owner, увеличить owner/version, повысить нового владельца до TENANT_ADMIN и сохранить прежнего TENANT_ADMIN, audit/receipt/outbox.
- Сериализовать paid coverage через краткоживущий billing fence; незавершённая FREE PREPARED смена тарифа не превращается в разрешение передачи.
- Отозвать старый autopay по owner-version binding; durable recovery доводит side effect после lost reply, не изменяя историю принятых PSP операций и не создавая согласия нового owner.

## Capabilities

### New Capabilities

- `tenant-ownership-transfer`: передача финансовых полномочий с fencing, receipt и восстановлением.

### Modified Capabilities

Нет.

## Impact

tenant-service, billing-service, gateway, proto и новые миграции владельцев БД. Публичные REST/DTO сверяются с blueprint; работа в собственном local E2E. Новые private RPC требуют точных SERVICE trust edges. Общая stage, реальный PSP, merge/deploy не включены. Поиск/уведомления общего tenant transport не активируются этой задачей.
