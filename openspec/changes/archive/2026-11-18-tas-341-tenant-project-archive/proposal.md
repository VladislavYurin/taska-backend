## Why

TAS-341: native TenantProjectGrpc не реализует ArchiveProject/RestoreProject, а прежний storage archive требует явный project ADMIN и не использует текущие root facts. Нужен безопасный tenant lifecycle проектов.

## What Changes

- Реализовать native archive/restore через текущие membership, capability, tenant-bound lock и проверку версии.
- Добавить аддитивные RPC/REST restore и project version; native команды используют If-Match и Idempotency-Key, legacy DELETE сохраняет совместимость.
- Сохранить квоту, UUID, историю, последний ADMIN, атомарность outbox и аудит административного пути.

## Capabilities

### New Capabilities
- `project-lifecycle`: tenant archive/restore и повторы.

### Modified Capabilities
Нет.

## Impact

project-service владеет состоянием и новой append-only квитанцией; grpc-common-lib владеет RPC, gateway — REST. Выполнить проверки общих контрактов/потребителей и собственный локальный mTLS E2E. Deployment только через уже разрешённый merge.
