# Proposal

## Why

TAS-547: при native upload-url наблюдался HTTP 500 рядом с истечением dependency deadline 5s. В develop `f1d92925` StorageQuotaStore оставляет Reactor TimeoutException без доменного статуса; общий gRPC mapper превращает его в INTERNAL. Влияние на production и конкретный dependency исходного случайного сбоя пока не подтверждены.

## What Changes

- Сохранить deadline 5s для policy/billing/owner, но выдавать управляемый UNAVAILABLE при его истечении; REST должен вернуть 503 DEPENDENCY_UNAVAILABLE.
- Детерминированно проверить каждый источник ожидания и границы сохранения upload intent, reservation, effects и counters; повтор того же ключа продолжает исходную операцию.
- Проверить прежние ACL/quota отказы и успешные signed PUT/confirm; описать безопасный retry без раскрытия внутренних сведений.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `tenant-attachment-upload`: контролируемый dependency timeout с сохранением идемпотентного состояния.

## Impact

Владельцы изменения — tenant-service StorageQuotaStore, issue-service PrimaryAttachmentPolicy и узкий REST error mapping api-gateway. Проверяются gRPC boundary и issue-service upload saga. OpenAPI описывает новый dependency Problem/503 только на trusted upload-url. Новые proto/REST поля, миграции, изменения TTL, ослабление guards, изменения TAS-459 и активация общего стенда не входят в scope. Отдельный PR; deployment возможен только после разрешённого merge.
