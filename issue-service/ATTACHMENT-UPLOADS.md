# Загрузка вложений tenant (TAS-365)

Профиль `tenant-storage` подключает публичные create-upload-url/confirm RPC и их REST
маршруты в gateway. Остальные методы native attachment API остаются закрытыми до
отдельной реализации. Учёт использует существующий [storage-протокол](../tenant-service/STORAGE-QUOTAS.md)
TAS-347, а не отдельный счётчик.

## Контракт и доступ

`POST /api/v1/projects/{projectId}/issues/{issueId}/attachments/upload-url` принимает
`fileName`, `contentType`, положительный `sizeBytes` и необязательный `Idempotency-Key`
(до 128 символов). Без ключа используется request ID; для повтора после сетевой ошибки
клиент должен сохранить исходный ключ. Изменение тела при том же ключе возвращает
конфликт. Повтор использует уже сохранённое окно PUT и не продлевает его. Tenant, actor и scope берутся из проверенного контекста, project/issue —
из пути и primary источников. Ключи S3 выдаёт только сервер. Ответ содержит временную
PUT capability и `objectKey`, который служит идентификатором подтверждения.

Клиент выполняет PUT с исходными Content-Type и размером, затем POST `.../confirm`
с теми же `objectKey`, `fileName`, `contentType`. Подтвердить загрузку может исходный
actor при актуальном доступе к tenant, проекту и issue. Чужой key не становится
основанием для чтения или удаления S3. Проверяются текущие credentials, ACL,
lifecycle, ограничения и feature flag; недоступный primary источник закрывает доступ.
Максимальный размер поступает из опубликованной или сохранённой retired версии
каталога, `max_attachment_bytes`; для принятой операции условия сохраняются.

Выключение uploads запрещает новые admission, но разрешает завершить уже принятый
intent при сохранении остальных проверок. Коммерческий bypass требует актуального
primary GLOBAL_ADMIN и web MFA; административный WEB_GLOBAL требует MODIFY.
Один текст роли в PAT не даёт bypass. Bypass сохраняет фактический расход и проверки
объекта. Текущая реализация primary auth не добавляет отсутствующую поддержку
административных PAT/OAuth.

## Атомарность и неизвестные результаты

Миграция issue `issue-attachment-upload-0023` добавляет `attachment_uploads` и
`issue_attachments` с FORCE RLS, аудитом и неизменяемыми идентификаторами.
До PUT сохраняются durable intent, root reservation и окно capability не длиннее
пяти минут. Reservation действует 15 минут. Bucket должен иметь ENABLED versioning;
приложение проверяет его, но не меняет настройки общего bucket.

Confirm фиксирует конкретную staging version, копирует её в собственный final key
и проверяет обе версии через HEAD и потоковый GET/SHA-256. Metadata вложения и
owner CONFIRMED/outbox сохраняются в одной транзакции, в том числе при внутреннем
Activate от quota root или его reconciler; root COMMIT учитывает actual
bytes один раз. Потеря ответа или ошибка metadata после root COMMIT восстанавливается
повторным confirm либо техническим reconcile.

Перед COPY в короткой транзакции сохраняется `copy_started`. Повтор не посылает
второй COPY; автоматический retry SDK отключён только для этого запроса. Положительное доказательство final version обязано совпасть с серверными
metadata intent/source-version. Если результат неизвестен и положительного доказательства
ещё нет, возвращается `COPY_OUTCOME_UNKNOWN`, а резерв удерживается. Отсутствие объекта
при HEAD не доказывает, что незавершённый запрос COPY не завершится позднее. Claim COPY
и отмена разделяют owner advisory lock; отмена не может освободить такой резерв.
При неустранимо неизвестном результате требуется операторское расследование, а не
обнуление счётчика или повтор COPY. Ошибки confirm не удаляют произвольный объект.

## Восстановление и подключение

`AttachmentAdmissionService.CheckAdmission` — приватный вызов tenant-service к
issue-service с сохранением исходного credential/grant. Производственные configuration
подключают policy к primary auth/tenant/billing/project/issue источникам и подписанные
QuotaAuthority/StorageQuotaOwner clients. Нужны trust edges для этих RPC и каталог
с положительным max_attachment_bytes; отсутствие wiring не заменяется allow-all.

`AttachmentAdmissionService.ReconcileUploads` обрабатывает страницу до 100 intent
только внутри проверенного tenant, с UUID keyset cursor. Он завершает запрошенные
confirm и activation либо отменяет expired/cancel-pending intent после доказанной
очистки собственных keys. Нужен заранее выданный SERVICE credential с issuer
issue-service, purpose `STORAGE_CLEANUP`, scopes `write` и `storage:recover`, текущим
service trust и transport action `reconcileAttachmentUploads`. Ответ содержит `failed` и
отдельный счётчик `data_loss`: ненулевой `data_loss` требует операторского расследования;
временная недоступность источника учитывается только в `failed`. PAT нельзя преобразовать
в SERVICE. Планировщик и автоматический обход всех tenant этим API не создаются.

Проверки: `AttachmentUploadIntegrationTest` использует собственные PostgreSQL,
app roles/RLS, mTLS quota RPC и versioned S3; policy ports в нём управляемые.
`python3 scripts/harness/attachment_upload_e2e.py` поднимает отдельные семь приложений,
PostgreSQL/Kafka/S3 и проверяет REST → текущие primary источники → signed PUT → confirm,
повторы, учёт, tenant/project isolation и отзыв membership. Оба runner очищают только
свои ресурсы; общая stage среда не используется. Результаты хранятся в ignored target.
