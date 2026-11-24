# Proposal

## Why

TAS-367 завершает native lifecycle вложений после TAS-365/TAS-347. В develop уже есть upload/confirm, immutable manifest и byte ledger, но публичные native list/download/delete закрыты, а повторяемый запуск удаления отсутствует. Состояние deployment этим изменением не утверждается.

## What Changes

- Список и скачивание только CONFIRMED вложений с текущими credentials, tenant/project/issue ACL и capability. Согласно актуальному L10 скачивание идёт через авторизованный gateway, включая Range и повторную проверку доступа во время потока; S3 download URL не возвращается.
- Durable принятие удаления немедленно скрывает вложение. Worker удаляет только принадлежащие manifest версии и освобождает bytes однократно по primary DELETED receipt. Ошибка S3, повтор и restart сохраняют intent и расход.
- Tenant-bound SERVICE cleanup с ограниченной пачкой, lease/fence, метриками и эксплуатационной инструкцией; существующий expired upload cleanup также получает повторяемый запуск.
- PROJECT_CLEANUP остаётся закрытым без подтверждения разрешённого удаления проекта. Реализация TAS-459, avatar lifecycle TAS-366 и retention purge TAS-520 не входят в задачу.

## Capabilities

### New Capabilities

- `tenant-attachment-lifecycle`: защищённое чтение и повторяемое удаление tenant-вложений.

### Modified Capabilities

Нет; правила upload/confirm из `tenant-attachment-upload` сохраняются.

## Impact

REST/OpenAPI и gateway, additive RPC в grpc-common-lib, issue-service (новая миграция и worker), root releaseUsage RPC в tenant-service, versioned S3 reader в storage-lib. Затрагиваются native trust routes, контрактные и PostgreSQL/S3 проверки, owned native E2E и документация. Legacy профиль сохраняется; автоматический deploy develop возможен только после разрешённого merge.
