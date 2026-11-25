# Native чтение и удаление вложений — TAS-367

Профиль `tenant-storage` с проверенным RPC context открывает list/download/delete после TAS-365. Legacy профиль продолжает свой API. Авторитетные контракты: [OpenAPI](../api-gateway/src/main/resources/static/openapi.yml), [attachment RPC](../grpc-common-lib/src/main/proto/v1/issue-attachment.proto), [quota RPC](../grpc-common-lib/src/main/proto/v1/project-quota.proto). Upload/confirm описаны [отдельно](ATTACHMENT-UPLOADS.md).

## Публичный API

Все операции проверяют текущие credentials/scopes, tenant, project и issue ACL, typed attachment capability и существование issue. Все path IDs передаются в native RPC. Чтение разрешено VIEWER; delete требует write и attachment.delete. Отключение uploads не выключает чтение. Storage READ_ONLY запрещает обычный direct delete, включая tenant admin; web administrative bypass требует действующего MFA/grant и сохраняет аудит.

List возвращает только CONFIRMED metadata. Native `GET .../attachments/{id}/download-url` возвращает `downloadUrl` с относительным gateway path `.../attachments/{id}/content` и checksum (в RPC поле называется `url`). Клиент передаёт Bearer при каждом GET/HEAD/Range на этом пути. S3 download URL и object keys не передаются. Legacy download-url сохраняет прежнюю семантику.

Content GET/HEAD поддерживает один диапазон `bytes=start-end`, `bytes=start-`, `bytes=-suffix`. Ответы 200/206 содержат Content-Length, Accept-Ranges, безопасный Content-Disposition, private/no-store и nosniff; 206 содержит Content-Range. Недопустимый/множественный Range даёт 416 после авторизации. ReadAttachmentContent — private RPC: metadata_only либо chunk 1..1048576 bytes, offset, expected_checksum; ответ без S3 ключей/URL. Только pinned final version. Каждый chunk получает свежую проверку credentials и ACL; получение chunk ограничено 5s. Независимый от backpressure watchdog повторяет проверку каждые 15s с общим timeout 10s и отменяет write при отказе/недоступности, соблюдая lease ≤30s даже при медленном клиенте. Уже переданные bytes отозвать невозможно.

## Удаление и учёт

DELETE 204 подтверждает durable принятие, а не синхронный purge. Одна transaction сохраняет retained deletion job, DELETE_PENDING, manifest version и outbox. Вложение сразу недоступно для чтения. Повтор не создаёт новый job, не сбрасывает retry и не освобождает bytes.

Worker claim: lease 60s, возрастающий epoch, попытка не длиннее 20s; complete/retry — CAS текущего unexpired epoch. Сбой оставляет PENDING и next_attempt_at через 60s. Owner очищает только два immutable server-generated key своего manifest ограниченными batches. До конца исходного PUT window очистка откладывается. После доказанного отсутствия owner сохраняет DELETED receipt/outbox. Private `ReleaseStorageUsage` принимает только STORAGE_BYTES и immutable operation/resource/reservation/fence, не принимает actual amount и вызывает существующий root ledger. RELEASE_USAGE однократен и требует primary DELETED proof. Manifest, metadata и receipt сохраняются для audit/reconcile/FK; retention purge относится к TAS-520.

STORAGE_CLEANUP SERVICE: issuer issue-service (либо tenant-service при делегированном owner/root callback), service_identity issue-service, scopes write/storage:recover, current generation и purpose. CheckAdmission проверяет scope по фазе: READ требует read, admission/finish/delete phases требуют write; техническая проверка write-команды не даёт общего права чтения. Продолжение delete дополнительно требует сохранённый tenant-bound job. Accepted upload сам по себе не даёт delete authority. Production PROJECT_CLEANUP закрыт без proof разрешённого project delete; его оркестрация относится к TAS-459. Global avatar namespace не участвует.

## Повторный запуск и наблюдение

На native issue-service задать `taska.attachments.cleanup-tenants` как список UUID (не более 100), `taska.attachments.cleanup-interval` (по умолчанию 30s). Пустой список ничего не запускает. Нужны managed trust edges STORAGE_CLEANUP: self ReconcileDeletions/ReconcileUploads, issue→root GetQuotaReservation/ReleaseStorageUsage/ReleaseQuota/CommitQuota, root→issue CheckAdmission и StorageQuotaOwner callbacks; только нужные methods и scopes. SERVICE не получают полномочия создавать uploads.

Scheduler выбирает до 100 due deletion jobs на tenant, получает свежий context на каждую попытку; expired upload reconcile проходит по одному intent за tick с cursor и возвратом к началу. Private ReconcileDeletions принимает tenant из проверенного context, limit 1..100 и after_id, возвращает processed/completed/failed/data_loss/next_cursor. Для долгих страниц caller получает новый SERVICE context перед следующим вызовом; после TTL старый context не продлевается. Не передавать PAT вместо SERVICE и не расширять purpose.

Метрики без tenant/user/file labels: `taska.attachments.cleanup.pending` (последний полный scan настроенной worklist), `last_scan_epoch_seconds`, `attempts{kind,outcome}`, `scan_failures`. Старый last_scan при ненулевых failures означает, что pending snapshot не свежий. В job `last_code` содержит только bounded protocol code с фазой PURGE/RELEASE/ATTEMPT; DATA_LOSS требует расследования immutable manifest/receipt и ledger, не сброса квоты. Exception text, keys и credentials не сохраняются.

При сбое проверить readiness S3/versioning и primary authority, дождаться конца PUT window и next_attempt_at, повторить bounded reconcile либо дождаться scheduler. После restart job подбирается после истечения lease. Нельзя сбрасывать общие counters, менять immutable keys/versions, удалять receipt или сканировать bucket по tenant prefix. Rollback: остановить worklist и закрыть новые методы, сохранив очередь/учёт; destructive data rollback не нужен.


Очистку проекта оркестрирует [TAS-459](../project-service/PROJECT-DELETION.md).
Она подтверждает primary project intent, сохраняет локальный fence и создаёт
accepted attachment deletion jobs по своему manifest. Далее работает тот же
STORAGE_CLEANUP с retained object/usage receipts. Прямой вызов
Use.PROJECT_CLEANUP остаётся закрыт: наличие такого enum само по себе не даёт
права удалять объекты. Metadata физически удаляется только по принятому project intent и terminal
object proof; retirement фиксируется после очистки содержимого; retained identities/receipts сохраняются.
