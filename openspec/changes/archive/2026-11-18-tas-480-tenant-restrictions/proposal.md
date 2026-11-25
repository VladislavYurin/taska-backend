## Why

TAS-480: owner pause и platform block пока не имеют durable команд/таблицы. Root capability facts подставляют пустые independent restrictions. Нужны независимые причины, versioned lifecycle и запрет новых autopay admissions без изменения уже оплаченного периода.

## What Changes

- Добавить tenant_restrictions, журнал команд и actor/reason/result audit; owner меняет только свою паузу, проверенный GLOBAL_ADMIN — platform block.
- Подключить текущие root причины к tenant DTO, membership authority, capability/entitlement projections и autopay admission guard.
- Реализовать аддитивные RPC/REST pause/resume/platform block, optimistic versions, durable retries и outbox.
- Сохранить управление компанией/разрешённое финансовое чтение при закрытом content, независимость оплаты и периода.

## Capabilities

### New Capabilities
- `tenant-restrictions`: независимые ограничения доступа и текущая авторизация.

### Modified Capabilities
Нет.

## Impact

tenant-service владеет lifecycle/restrictions, billing-service — оплатой и admission autopay, gateway/proto — контрактами. Общие primary facts затрагивают project/workflow/issue. Проверить потребителей и owned E2E. Бизнес-вопросы A34/A42 записаны в TAS-535; пока сохраняется утверждённая access-matrix, предложенное фронтом расширение не включается молча.
