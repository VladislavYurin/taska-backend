# Proposal

## Why

TAS-365 разблокирована после слияния TAS-343 и TAS-347: задачи и storage accounting уже реализованы, но публичные upload/confirm не связаны с tenant ACL и долговечным резервом. Нужна загрузка без двойного расхода и потери учёта при сбоях S3/БД.

## What Changes

- Связать REST/RPC upload-url и confirm с текущими primary identity, project/issue ACL, lifecycle, flags и типизированными условиями каталога.
- Сохранять intent и серверные keys до capability, фиксировать конкретные версии и actual SHA-256 перед root COMMIT.
- Обеспечить повтор, concurrent confirm, атомарную metadata activation и ограниченное техническое восстановление.
- Сохранить честный usage при разрешённом GLOBAL_ADMIN bypass и отказ при нарушении целостности.

## Capabilities

### New Capabilities

- `tenant-attachment-upload`: приём, подтверждение и восстановление загрузки tenant-вложения.

### Modified Capabilities

Нет.

## Impact

Gateway/OpenAPI, общие protobuf и capability facts, billing catalog response, issue schema/owner/upload saga, tenant policy clients, project access check. Общая стадия не используется. Download/list/delete, глобальные аватары, планировщик всех tenant, административные PAT/OAuth и merge/deploy вне scope. Новый OpenSpec процесс появился в develop во время уже начатой реализации; требования здесь сверены с исходной Jira и действующими контрактами до завершения работы.
