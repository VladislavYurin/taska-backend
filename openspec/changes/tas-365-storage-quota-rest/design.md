# Design

## Context

Root возвращает FAILED_PRECONDITION с code=QUOTA_EXCEEDED и quota.kind=STORAGE_BYTES. Gateway уже умеет преобразовывать такой structured error в 422, но RestErrorContract не выбирает его для вложений.

## Goals / Non-Goals

Исправить установленный отказ upload-url. Не расширять новый формат на все ошибки вложений, project create или legacy маршруты; не менять лимиты и протокол reservation.

## Decisions

Выбирать problem contract только для POST точного attachment upload-url и structured QUOTA_EXCEEDED. Сохранить существующий problemStatus, который учитывает transport, без разбора текста сообщения. Добавить OpenAPI response и тест реальной сериализации gRPC details, включая отрицательные границы method/path/code и trusted mode.

## Risks / Trade-offs

Изменяется наблюдаемый ранее неправильный 400 на принятый 422. Ошибки 401/403/404 и соседние операции сохраняют опубликованный формат. Native E2E проверяет отсутствие успешного upload при quota refusal и последующий подтверждённый administrative bypass с честным учётом.

## Verification

Gateway focused tests, CI matrix и native attachment runner на чистом head; повтор runner после финального review. Logs/results остаются вне Git.

После объединения TAS-363 и TAS-365 migration inventory содержит восемь исходных changesets. Интеграционный storage test учитывает оба добавления и отдельно проверяет FORCE RLS и отказ runtime TRUNCATE для attachment_uploads и issue_attachments; сами changesets сохраняются.
