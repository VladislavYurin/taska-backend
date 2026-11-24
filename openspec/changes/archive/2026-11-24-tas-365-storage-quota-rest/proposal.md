# Proposal

## Why

TAS-365: на объединённом с develop коде native E2E воспроизводит HTTP 400 при исчерпанной storage quota вместо принятого HTTP 422. REST-маршрут upload-url ещё не выбирает существующий problem mapper.

## What Changes

- Опубликовать и реализовать HTTP 422 application/problem+json с QUOTA_EXCEEDED для POST upload-url.
- Проверить transport/details и сохранить формат прочих ошибок и соседних legacy операций.
- Повторить native REST/mTLS/versioned-S3 E2E и актуальное review.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `tenant-attachment-upload`: уточнить формат и границу quota refusal на REST upload-url.

## Impact

Gateway mapper routing, OpenAPI и regression tests; миграции, RPC и правила admission не меняются. Shared stage не используется.
