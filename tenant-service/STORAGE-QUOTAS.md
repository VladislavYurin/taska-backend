# Учёт storage (TAS-347)

`StorageQuotaStore` владеет byte reservations/effects в tenant DB. `StorageQuotaOwner`
в issue DB владеет attachment manifest и долговечным resource receipt. Это внутренний
протокол; публичный upload/confirm добавляет TAS-365, пользовательское удаление — TAS-367.
Глобальные аватары и исключённый company logo в этот протокол не входят (D166/D120).

1. Root резервирует положительное int64 количество под company lock. Свежая policy
   проверяет actor/credential/grant, tenant/project/issue ACL, lifecycle, доступность
   новых uploads, ограничения и типизированный catalog cap с версией. Значения тарифов
   не зашиты в код. Без policy/billing/owner операция закрыта с UNAVAILABLE.
2. Owner под тем же operation ID создаёт RESERVED manifest с серверными staging/final
   keys. Перед выдачей PUT capability TAS-365 обязан вызвать `recordUploadWindow`;
   окно не длиннее пяти минут и не выходит за deadline reservation. URL не передаётся
   в quota RPC/outbox. Одной клиентской declared size недостаточно для commit.
3. TAS-365 копирует **конкретную staging version** в final key и передаёт внутреннему
   `materialize` обе версии. Owner требует ENABLED bucket versioning, измеряет точные
   версии через HEAD + потоковый GET/SHA-256 и проверяет bytes/MIME/checksum. Actual
   bytes должны быть положительны и не превышать reserved. UPLOADED пока скрыт.
4. Root по primary owner receipt пишет COMMIT effect на actual bytes. Остаток
   reservation освобождается. После root transaction owner проверяет primary root
   COMMITTED proof и атомарно переводит manifest в CONFIRMED с metadata outbox.
   Повторы сохраняют исходную идентичность и не начисляют расход повторно.

Коммерческий bypass требует отдельного актуального разрешения policy. Он сохраняет
реальный расход и числовые/объектные ограничения. Signed clients сохраняют исходные
actor, credential kind, scopes и grant через service hop; read-only PAT не получает write.

## Отмена, удаление и восстановление

Root сначала фиксирует cancel intent, затем обращается к owner. Поздние materialize/
commit блокируются; owner сохраняет tombstone до очистки. Пока PUT окно открыто,
S3 недоступен или удалены не все версии, root удерживает ACTIVE reservation. TTL
сам по себе не доказывает отсутствие объекта.

Удаление подтверждённого файла сначала фиксирует DELETE_PENDING. Очистка затрагивает
только две точные keys собственного manifest; версии удаляются партиями до 100 с
проверкой отсутствия. Только после успешной очистки owner пишет DELETED receipt;
`releaseUsage` либо `reconcileManifests` однократно применяет RELEASE_USAGE. При ошибке
metadata и used bytes сохраняются. Произвольный bucket/prefix scan не используется.

`reconcile()` ограничен 100 root операциями за вызов: expired/cancel-pending и
committed/pending-activation. `reconcileManifests(after, version)` обрабатывает максимум
100 primary owner receipts с keyset cursor и проверяемой версией манифестов. При смене
версии скан начинается заново; частичная страница не считается полным tenant total.
Неизвестный confirmed manifest без matching root admission вызывает DATA_LOSS.
Counters восстанавливаются из долговечных COMMIT/RELEASE_USAGE effects; неизвестные
и неочищенные операции не освобождают bytes.

Снижение лимита сохраняет файлы и actual usage, даже если used превышает новый limit.
`restrictions(tenant)` возвращает независимую storage причину OVER_QUOTA.
TAS-495/TAS-354 используют этот сигнал для независимого OVER_QUOTA; storage не снимает
pause/dispute/security/неоплату. Policy запрещает DIRECT_DELETE при storage READ_ONLY
(D140); PROJECT_CLEANUP допустим только как техническое продолжение разрешённого
удаления проекта с актуальным purpose proof. Сам enum purpose не является разрешением.

Миграции добавлены отдельно: tenant 0012 и issue 0018. RLS/audit и неизменяемые receipts
сохраняются после удаления S3 объекта. FK manifest удерживает issue до согласованного
позднего lifecycle purge (TAS-367/TAS-520); удалять retention записи напрямую нельзя.

## Подключение и проверка

Tenant configuration подключает signed storage owner client при наличии stub/security;
issue profile `tenant-storage` открывает только перечисленные verified internal RPC.
Производственные policy providers намеренно требуют подключения актуальных источников
доступа в задачах API/lifecycle; отсутствие источника не разрешает операцию. S3 adapter
читает настройку versioning, но не включает её в общем bucket автоматически.

GetTenantQuotas выдаёт SEAT/TASK_LIFETIME/PROJECT/STORAGE_BYTES и отслеживает версии всех
источников. Недоступный storage source не подменяется нулём.

`StorageQuotaIntegrationTest` запускает production stores/handlers на двух собственных
PostgreSQL с app roles, mTLS/JWS и versioned S3 Testcontainer. Проверяет actual/reserved,
границу 20 000 000 bytes, pinned versions, int64 больше 2 GiB, конкуренцию, повторы,
отмену с потерянным ack, ошибки очистки, downgrade и сохранение прав PAT. Это локальный
межсервисный E2E; публичный REST, deployed stage и Kafka delivery этой проверкой не покрыты.
