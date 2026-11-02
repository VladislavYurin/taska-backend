# B2B: эксплуатационные алгоритмы для реализации

Это техническая постановка автоматизации, не отчёт о выполненном cutover/restore. Никакие общие данные или окружения при аналитике не очищались. Базовый код: `af7ab57ead44304b89bbd47dabeeba45b761ce16`; business rules — [единая спецификация](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880), B17/B18. Реализация: TAS-369/370/371/374/375/376/379/481/489/492/520.

## R01. Чистый старт — CLI и run manifest

TAS-369/370 реализуют подготовку схем/initializer и manifest; TAS-371 реализует команду `b2b-cutover` в существующем harness как dry-run/plan/apply/resume/verify. Это имя проектируемой команды, сейчас её в harness нет. Вход: environment ID, artifact SHA, schema versions, allowlist test-tenant/user data selectors, immutable plan hash, operator, backup receipt. Секреты передаются ссылками на секрет-хранилище, не аргументами с raw secret.

1. **Preflight:** проверить подтверждённую среду, совместимость миграций, отсутствие активного traffic admission, перечень всех БД/buckets/topics/queues, GA/config allowlist, expected current versions. Несовпадение inventory/version → stop до удаления.
2. **Plan:** сформировать counts/IDs/hash удаления по сервисам, S3 manifest конкретных keys, Kafka/outbox generation fencing, revoked sessions/invites/grants. Dry-run не пишет данные. Пользователь подтверждает конкретный plan hash при фактическом запуске; старый hash после drift неприменим.
3. **Backup:** подписанный/хешированный consistency manifest БД + object versions + captured positions. Проверить читаемость контрольной выборки. Отсутствующий backup receipt блокирует apply.
4. **Apply:** закрыть admission, остановить/revoke старые workers/tokens, по шагам выполнить scoped cleanup тестовых данных. GLOBAL_ADMIN, служебные настройки и secrets сохраняются. Миграции создают новые схемы и политики; demo USER off, default workflow копируется в tenant.
5. **Verify:** новая identity с прежним тестовым email не получает старые memberships; A/B fixtures изолированы через REST/RPC/jobs/files; нет старых backlog effects; count checks и RLS probes. Только затем открыть readiness. Failing check оставляет maintenance.
6. **Resume:** per-step status/CAS/fencing/token, повтор не изменяет result завершённого шага. После появления tenant traffic откат приложения только к tenant-aware совместимой версии. Откат к legacy, снимающий tenant checks, запрещён. Рестор финансовых фактов не производится обычным DB rollback.

Выход JSON: runId, environment, source SHA, planHash, steps(status,startedAt,completedAt,counts,checksum), failures, nextSafeAction. Логи/receipts вне Git, код команд/синтетические fixtures — в Git. Приёмка TAS-371: dry-run zero writes; drift stops; crash каждого шага→resume; preservation GA/config; negative A/B smoke. Реальный production запуск не является побочным эффектом теста или merge документации.

## R02. Восстановление одной компании

TAS-374 реализует tenant restore orchestrator + staging drill test, а не новую аналитику. Вход: targetTenant UUID, backupManifestId, restoreOperationId, expected tenant tombstone/security version, artifact SHA. Backup никогда не импортируется поверх всех global identities или соседней компании.

Порядок: закрыть admission только A → проверить tombstone/holds/backup checksums → развернуть snapshot в изолированные restore schemas → выбрать tenant-owned rows по registry и validate composite FKs → восстановить pinned object versions из manifest → сверить current global memberships/security/grants → reconciliation актуальных ledger/provider facts → перестроить usage/entitlement projections → RLS+ACL+object checks → открыть A. B продолжает работать, её counts/checksums сравниваются до/после.

Lifetime task counters не восстанавливаются до меньшего snapshot: использовать текущий подтверждённый high-water floor либо replay durable post-snapshot journal/WAL до доказанного watermark. COUNT(issues) не восстанавливает lifetime, поскольку удалённые задачи тоже считаются. Нет доказуемого floor → maintenance. Старые payment attempts не повторно отправляются PSP. Financial ledger восстанавливается только с последующей сверкой актуальных подтверждённых фактов; restore не отменяет refund/dispute/tombstone, поступившие после backup. Несогласованные отсутствующие chunks/files, зависший UNKNOWN, неверный scope или неопределённая retention policy → maintenance + явный failure, не best-effort live. Измерить RPO/RTO на drill и сохранить фактические значения; не объявлять SLA без измерений.

Приёмка: A рядом с B; missing object; повреждённый checksum; позднее событие после snapshot; deleted tenant; revoked owner; refund после backup; crash/resume. Результаты привязаны к SHA, не называются выполненными в этом аналитическом документе.

## R03. Конфигурация, rotation и операторские действия

TAS-376: versioned deployment/config templates для tenant-service/billing-service, migration/runtime DB roles, service identities, Kafka ACL, private object namespaces, callback ingress, secret references и readiness. Live PSP guard проверяет C02 и policy versions. Подключить автоматические config checks: runtime не superuser/BYPASSRLS; internal RPC недоступен из публичного ingress; debug/raw payload logging off; demo credentials off; webhook environment matches merchant; missing policy disables corresponding capability.

Ротация signing key: отдельные keysets RPC/auth; publish new public key with kid → подтверждение получения всеми verifier → переключить signer и прекратить старую подпись → держать old verification key до последней старой подписи + максимальный TTL выданного credential +5s skew → убрать прежний key; compromise имеет немедленный revoke с fail-closed, не обычное overlap. Для provider webhook secret проверять vendor contract и test/live раздельность. Test: good new key accepted, unknown/revoked rejected, старый вне overlap rejected, нет права расширить admin grant через integration.

TAS-481: локальная операторская bootstrap/recovery команда в auth-service или harness backend adapter. Она доступна только операторскому service identity, отсутствует в пользовательском REST/MCP. Первый GA создаётся initializer без полноценной сессии до подтверждения email. Operator выдаёт purpose-bound one-time enrollment flow; пользователь подтверждает доставку на новый адрес. При lost email другой текущий GA с completed MFA либо оператор после внепрограммной проверки личности запускает email change; прежние sessions/grants и MFA challenges отзываются, security version растёт, audit фиксирует who/why/target/result. Нельзя вводом произвольного email получить восстановление через публичный forgot-password. Procedure не разрешает неизвестному пользователю стать GA.

## R04. Fairness, наблюдаемость и retry

TAS-375: gateway rate budget keyed tenant+actor+credential class, отдельный global credential budget для no-tenant endpoints; atomic distributed admission и bounded concurrency, превышение →429/Retry-After. Не выводить тарифный лимит из инфраструктурной настройки. GLOBAL_ADMIN также имеет transport safety cap, чтобы случайный запрос не исчерпал платформу.

TAS-489: round-robin/weighted fair dequeue по tenant, bounded per-tenant inflight, lease+fencing, compare-and-set ack. Технический default lease60s/heartbeat20s; I/O timeout не превышает lease без heartbeat. Backoff full jitter exponential от 1s до 5min; после 10 технических неудач → quarantine/DLT с диагностикой. UNKNOWN payment/refund остаётся reconciliation, не terminal failed на основании retry count. Business denial permanent не крутить бесконечно. Replay только того же tenant/eventId, с текущей авторизацией и audit. Изменение TTL/worker restart не переписывает tenant исходной работы.

TAS-492: метрики oldest pending outbox/reconciliation, invalid tenant envelope, permission failclosed, lease expiry, quarantine depth, quota drift/reservations age, unknown financial operations, rejected duplicate effect, policy readiness. Высококардинальные tenant/user identifiers — в защищённых trace/log, не unrestricted Prometheus labels; dashboard выводит scoped агрегаты. Alert содержит run/operation ID без raw tokens/private text. Runbooks на каждый сигнал: inspect scope → pause affected admission/worker → reconcile authoritative owner → audited retry → verify invariants → resume.

## R05. Автоматический release evidence gate

TAS-379 реализует команду/CI job, собирающий JSON evidence от isolation/auth/files/jobs/billing/UI/cutover/restore suites на **одном проверяемом SHA**, и machine-readable go/no-go report. Это отдельная задача на код security regressions и validation, независимый human security sign-off остаётся процедурой выпуска, а не аналитикой в developer queue.

Обязательные проверки: A/B list/search/count/aggregate; forged path/Header/service identity; stale/revoked grant; tenant RLS with reused connection; admin READ scope mutation; admin grant issuance без MFA; export/download после revoke; signed callbacks tamper/duplicate/out-of-order; billing UNKNOWN+timeout; refund+dispute; final docs/commercial/retention readiness; cutover dry-run/resume; restore A beside B. Critical/high regression, mismatched SHA или отсутствующий required receipt → no-go. Отчёт не запускает production deploy. Владелец релиза отдельно разрешает фактический rollout и реальные деньги.

## R06. Retention engine

TAS-520 реализует policy validation + scheduler + holds. Policy version берётся из platform configuration; данные конкретной deletion operation содержат snapshot policy/hold generation. Любой purge command сначала проверяет subject tombstone, dataset, legal hold, dueAt и version; отсутствие обязательного основания/срока → HELD с reason и readiness false, не молчаливое бесконечное хранение. Существующий hold не снимается при retry/deployment.

Worker выполняет ERASE/ANONYMIZE/RESTRICT для конкретного сервиса/набора с checkpoint, проверяемой квитанцией и без лишних PII в audit. Повтор одного purge не удаляет другой tenant. Снятие hold требует явной административной команды с evidence. Документы после удаления выдаются только по подтверждённой C06 процедуре и scoped case, без восстановления membership. Приёмка: policy unset, held, due/not-due boundary, erase retry, lost callback, changed version, backup tombstone enforcement, denied unauthorized document retrieval.
