## Why

TAS-546: полный native project ACL runner останавливается на зависимостях fixture и ожиданиях REST, хотя отдельный TAS-545 project/outbox subset работает. Fixture project не содержит всех SERVICE edges, уже нужных workflow/issue capability sources; часть негативных сценариев смешивает quota/duplicate и синхронную компенсацию с competing recovery.

## What Changes

- Согласовать owned native fixture с текущими primary источниками и централизовать минимальные source edges без дублирования у наследников.
- Сверить негативные HTTP ожидания с действующими REST/mapper и фактической причиной отказа; проверять неизменность quota/domain/outbox.
- Разделить детерминированную синхронную компенсацию и unknown-outcome recovery, сохранив завершение принятой операции после отзыва initiator и flag-off.
- Выполнить полный project ACL runner на результате TAS-363/365 и применимые регрессии наследников, обновить документацию.

## Capabilities

### New Capabilities
- `native-project-harness`: воспроизводимая owned native project ACL/quota/recovery приёмка.

### Modified Capabilities
Нет.

## Impact

Планируемое изменение harness Python/Java probes, тестов и документации. Базовый develop 887eb292; финальная версия включает согласованную интеграцию TAS-363/365. Production Java, REST/proto/schema и guards не меняются ради теста; shared-stage активация и полная продуктовая приёмка вне scope. Фактически развёрнутое поведение не утверждается.
