# B2B: алгоритмы и переходы состояний

Статус: целевой дизайн SA-2026-11-03, а не реализованный функционал. Бизнес-источник — [единая спецификация, D01–D253](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880). Исходный код инвентаризирован на `af7ab57ead44304b89bbd47dabeeba45b761ce16`. Структуры БД — [target-data-model.md](target-data-model.md), transport DTO — [contracts/README.md](contracts/README.md), проверки доступа — [access-control.md](access-control.md). Дальнейшие правила не заменяют согласованные бизнес-исключения.

## L01. Общие правила выполнения команды

1. Проверить identity, тип канала, актуальность grant/session, явную область, уровень READ/MODIFY. Затем определить tenant из доверенного контекста; пользовательские `tenantId`, `ownerId`, role и billing status не являются доказательством полномочий.
2. Прочитать актуальные membership/owner/ACL, lifecycle, ограничения и необходимые версии. Недоступный обязательный источник прав → `503`, а не разрешение по устаревшему allow. Чужой или невидимый ресурс → одинаковый `404`; запрещённая операция над видимым → `403` с машинным reason.
3. Для команды с эффектом создать запись идемпотентности в сервисе-владельце. Ключ включает actor/grant, tenant или явно GLOBAL, operation type и клиентский ключ; hash содержит нормализованный бизнес-payload. Повтор того же payload возвращает тот же результат/operationId, другой payload → `409`. Идемпотентность не заменяет повторную авторизацию на чтение результата.
4. Локально заблокировать агрегат или выполнить CAS по version; проверить инварианты непосредственно перед записью. Несовпадение If-Match/version → `412`, отсутствие обязательной версии → `428`, повтор требует свежего объекта. В транзакции изменить агрегат, увеличить version, добавить outbox и запись результата. Внешний RPC/PSP/S3 не входит в эту SQL-транзакцию.
5. Асинхронную команду вернуть как durable operation с `202`. Worker арендует шаг с fencing/version, выполняет идемпотентный эффект, фиксирует результат. После timeout неизвестный внешний результат → `UNKNOWN`, сверка исходной операции; не создавать новый платёж/возврат под новым ключом.
6. Реестр инвариантов обязателен и для GLOBAL_ADMIN: достоверность финансового факта, сумма, отсутствие двойного эффекта, системная целостность, scope предъявленного grant. Административный обход бизнес-ограничения фиксируется в аудите с причиной, не маскируется под обычного владельца.

## L02. Жизненный цикл компании и FREE admission

`PROVISIONING → ACTIVE` для FREE; `PROVISIONING → AWAITING_PAYMENT → ACTIVE` для paid. Возможны `AWAITING_PAYMENT → AUTO_CLOSED | OWNER_CANCELLED`; живые состояния → `DELETING → DELETED`. Ошибка шага provisioning сохраняется в операции, не требует нового lifecycle компании. `ownerPaused`, `platformBlocked`, открытые споры и причины READ_ONLY независимы от lifecycle.

| Команда / событие | Предусловие и атомарный результат | Повтор / конкурентный случай |
| --- | --- | --- |
| Создать FREE | Проверенный глобальный аккаунт; опубликованная версия тарифа. В tenant DB захватить право FREE по стабильному identity marker, создать компанию и owner membership/роль в одной транзакции либо зарезервировать marker в этой же БД. | Два параллельных запроса одного владельца не создают две FREE-компании. Право закрепляется за конкретной компанией; членство в чужих компаниях не мешает. |
| Создать paid | Создать tenant UUID, owner, immutable `creationDeadlineAt = createdAt + configured 30 days`; платёжное намерение использует опубликованную версию. FREE marker не расходуется. | Retry и новый invoice не продлевают deadline. |
| Provisioning | Шаги: registry/owner → private workflow/template copy → baseline quota → FREE entitlement или billing invoice → readiness. Ключ каждого шага `(tenantId, provisionOperationId, step)`. | Crash после remote success возобновляет шаг по тому же ключу. ACTIVE только после готовности обязательных шагов и допустимого покрытия. |
| Первое подтверждение оплаты | Достоверная полная оплата, сумма/валюта/invoice связаны сервером. Для PSP начало покрытия `succeededAt`, для WIRE `confirmedAt`. | Тот же факт не даёт второй период; company pause/block не снимаются. |
| Истёк срок создания | Только paid-creation без первой полной оплаты, `now >= creationDeadlineAt` → AUTO_CLOSED; рабочий FREE и renewal сюда не попадают. | Транзакционный конфликт с оплатой решается после перечитывания financial fact. |
| Своевременная оплата, поздняя доставка | Все необходимые деньги фактически получены не позже invoice deadline и creation deadline; AUTO_CLOSED допускает возврат к активации той же компании (D216). | OWNER_CANCELLED/DELETING/DELETED не оживают; partial/late payment не является исключением. Независимая блокировка остаётся. |
| Отмена владельцем | До первой оплаты → OWNER_CANCELLED; не даёт FREE, не удаляет аккаунт. Пришедшие деньги сохраняются и возвращаются через settlement case. | Гонка cancel/payment сериализуется по tenant operation fence; результат подтверждённого платежа не теряется. |

FREE marker хранит HMAC канонического подтверждённого email и идентификатор использовавшей право компании. Повторная регистрация того же подтверждённого адреса не обнуляет право; разные адреса не связываются fingerprinting. Смена email не освобождает уже использованное право. Срок и основания определены D253 / [retention-policy.md](retention-policy.md): минимальный purpose-bound marker с ежегодной проверкой необходимости; lawful erasure обрабатывается явно, HMAC не считается анонимизацией.

## L03. Membership, приглашение и передача владельца

Membership: `ACTIVE → SUSPENDED → ACTIVE`, `ACTIVE|SUSPENDED → REMOVED`; восстановление удалённого участника выполняется новым согласованным добавлением, старые grants не оживают. INVITED — состояние приглашения, не активная identity. Владельца и последнего ACTIVE TENANT_ADMIN обычная команда не удаляет/не понижает/не блокирует. Счётчик мест включает ACTIVE, SUSPENDED и действующие резервы приглашений.

Invitation: `PENDING → ACCEPTED | REVOKED | EXPIRED`. TTL по умолчанию 7 суток, snapshot expiresAt; resend не создаёт новый резерв, не изменяет исходный срок. Токен криптографически случайный, в БД только hash; повторный resend инвалидирует старую ссылку. Для существующего аккаунта принятие требует обычной web identity с подтверждённым email приглашения; для нового — проверенную активацию identity по invitation flow. Оно конвертирует один reservation в одно membership в транзакции tenant-service. При финансовом READ_ONLY разрешено принять старое действующее приглашение; при pause/block запрещено, TTL продолжает идти. Уже существующий участник не занимает второе место, лишний резерв освобождается.

При принятии список проектов перепроверяется по текущим tenant/ACL/status; недоступные назначения пропускаются с явным списком причин. Участие в компании сохраняется даже при нуле выданных проектов. Утрата отправителем TENANT_ADMIN/выход отзывает его PENDING приглашения. Удаление/приостановка membership увеличивает security version, отзывает tenant-сессии и интеграционные grants, удаляет активные проектные назначения; восстановление не переиспользует старые права; SUSPENDED TENANT_ADMIN возвращается MEMBER, новые project grants задаются явно.

Передача владельца (TAS-473): ordinary owner, действующий paid coverage, флаг, ACTIVE получатель в той же компании; pause разрешена, platform block/dispute запрещены. Сверить ожидаемого старого owner и tenant version; под lock компании сделать нового owner + TENANT_ADMIN, прежнего оставить TENANT_ADMIN; создать outbox для отзыва старого autopay и обновления capabilities. До завершения отзыва billing проверяет owner version и не принимает старый mandate. Требуются явное подтверждение действия и действующая сессия, дополнительный OTP отсутствует (D248). Смена только финансовых прав не переносит/переписывает счета и факты.

## L04. Квоты и распределённые reservations

Авторитетный счётчик мест/проектов/байтов — tenant-service; lifetime task counter — issue-service рядом с созданием задачи. Не вычислять admission из eventually consistent dashboard.

Reservation: `ACTIVE → COMMITTED | RELEASED | EXPIRED`. Ключ `(tenantId, resourceKind, operationId)`, amount > 0, owner aggregate и expiry. Admission под lock счётчика проверяет `used + reserved + requested <= limit`; возврат прежнего reservation не увеличивает reserved. Commit переносит reserved в used однократно; release/expiry освобождают только ACTIVE. Счётчики не могут стать отрицательными.

Для удалённой операции reservation не истекает, пока владелец подтверждает незавершённую операцию/неизвестный commit: sweeper проверяет status у domain owner. При недоступности owner оставляет резерв и повторяет; не освобождает место на основании одного timeout. Для uploads истечение допускает release только если объект не COMMITTED; неподтверждённый объект удаляется sweeper. Commit после EXPIRED запрещён, требуются новый reservation и повторная проверка; доменный объект не становится видимым до подтверждения admission.

Project saga: reserve → создать недоступный PROVISIONING aggregate → commit reservation → открыть проект. Если commit уже успешен, crash перед открытием восстанавливает тот же проект; compensation удаляет provisional aggregate и освобождает только соответствующую запись. Архивирование не освобождает квоту. Hard-delete сначала закрывает проект, удаляет доменные данные/файлы повторяемыми шагами, затем один раз освобождает место; временно удержанный резерв безопаснее двойного использования.

Создание задачи: в одной issue DB транзакции проверить `(tenantId, projectId)`, admission/capabilities, idempotency, атомарно увеличить lifetime counter и вставить задачу/outbox. Квота FREE допускает переход 49→50, не 50→51. Ошибка/rollback и повтор idempotency не увеличивают счётчик. Удаление/закрытие/архив не уменьшает. На paid счётчик продолжает расти. После paid→FREE `count == 50` запрещает только CREATE_TASK; `count > 50` в любом живом проекте даёт tenant READ_ONLY. Накопительный счётчик нового UUID проекта начинается с нуля.

## L05. Invoice, деньги и календарь

Все суммы EUR/USD — целые minor units; decimal rate фиксируется в invoice, округление HALF_UP один раз при выпуске. `gross = net + tax`, налог не добавляется поверх согласованных 20 EUR; tax breakdown нельзя вычислять выдуманной ставкой. Отдельный номер документа выдаёт billing-service транзакционно, не UUID пользователя. Issued snapshot продавца/покупателя/строк/FX неизменяем; корректировки — отдельный связанный документ и факт.

Invoice: `DRAFT → OPEN → PARTIALLY_PAID → PAID`, допускается OPEN→PAID; OPEN/PARTIALLY_PAID→EXPIRED, DRAFT/OPEN→VOID при допустимой отмене. PAID не превращается обратно в OPEN после refund. Partial/late факты сохраняются отдельно даже при EXPIRED/VOID. Новый факт может закрыть EXPIRED invoice только по разрешённому late-renewal/manual outcome и с отдельным audit; иначе settlement case. Уникальная business key invoice включает tenant, subscription, purpose, целевой период/operation, planVersion и currency: PSP и WIRE работают с одним обязательством, не генерируют по счёту каждый.

Coverage status: `GRANTED | REVOKED | SUPERSEDED`. Активность вычисляется `status == GRANTED && startAt <= now && now < endAt`; FUTURE/ACTIVE/EXPIRED — представление относительно времени, не job как источник истины. Увеличение entitlement version и outbox сопровождает изменение покрытия. Старый финансовый факт не перезаписывает более новую независимую запись.

Календарь UTC: хранить исходный anchorDay и anchorTime; конец очередного периода = то же время следующего календарного месяца, день `min(anchorDay, daysInMonth)`. Следующий месяц снова использует первоначальный anchorDay: 31 января → 28 февраля → 31 марта. Интервалы полуоткрытые `[start,end)`, миллисекундный transport не меняет серверную точность. На `now == endAt` прежнее покрытие уже не активно, grace отсутствует.

| Случай | Начало нового периода | Конец / последствия |
| --- | --- | --- |
| Первая PSP оплата или возобновление после gap | actual provider succeededAt | Новый календарный месяц, исходный anchor от начала |
| Первая/возобновляющая WIRE оплата | GLOBAL_ADMIN confirmedAt | Новый календарный месяц; receivedAt определяет своевременность, не начало |
| Своевременное раннее продление | Конец текущего периода | Следующий месяц по исходному anchor, gap не оплачивается задним числом |
| Late WIRE renewal | confirmedAt после ручной сверки | Если tenant не deleted/blocked, новый месяц от подтверждения (D247) |
| Более дешёвый paid | Подтверждённая отдельная новая оплата | Новый месяц, прежний coverage SUPERSEDED, без прибавления остатка и автоматического refund |
| Paid→FREE | Успешное подтверждение выбора + FREE eligibility | Немедленные FREE limits; старое paid покрытие SUPERSEDED, история остаётся |
| FREE→paid после downgrade | Новая подтверждённая оплата | Старое ещё календарно неистёкшее покрытие не возобновляется |
| Более дорогой paid | Следующая граница периода после оплаты | До границы текущие права; тарифный upgrade не открывает права досрочно |

## L06. Нормализованный settlement PSP и WIRE

1. PSP webhook проверяет исходное тело/подпись, merchant/environment и поддерживаемую version. Inbox unique `(provider, merchantAccount, eventId)` записывается до ACK. Редирект браузера не создаёт payment fact. Факт успешного движения денег требует подтверждённого provider status; authorisation без capture не равна оплаченной подписке.
2. WIRE receipt регистрирует GLOBAL_ADMIN по банковской выписке: bankAccountRef, externalEntryId, amount, currency EUR, payerRef, receivedAt с timePrecision, immutable evidence hash/reference и confirmedBy/confirmedAt. Скриншот клиента не доказательство. Date-only выписка, пересекающая deadline, → WAITING_EVIDENCE; сервер не приписывает выдуманные часы.
3. Сопоставление проверяет server-owned invoice/payment attempt, tenant, purpose, currency и ожидаемую сумму. Не выводить tenant из недоверенной metadata. Неизвестное назначение → OPEN settlement case, сумма UNALLOCATED; компания не активируется.
4. Под lock invoice и источника денег дедуплицировать факт, проверить суммарные allocations. `received = netAllocated + unallocatedRemaining + confirmedNetReturned` в одной валюте; netAllocated уменьшен подтверждёнными reversal/refund/chargeback, исходные allocations остаются immutable. До полной суммы поступления только matched к invoice, но UNALLOCATED; финальная allocation создаётся при полном покрытии. Expiry возвращает все matched-but-unallocated поступления. Комиссия хранится отдельным фактом. PSP+WIRE не оплачивают одну invoice дважды: остаток второго источника идёт на отдельный case, не новый coverage.
5. Полная своевременная сумма покрывает invoice один раз и создаёт/связывает period. Недоплата остаётся PARTIALLY_PAID до deadline. Истёкший неполный invoice → EXPIRED + RETURN_REQUIRED для всего нераспределённого поступления. Это не отзывает независимую текущую подписку.
6. При timely first overpayment 25 EUR на invoice 20 EUR: 20 ALLOCATED, 5 UNALLOCATED/RETURN_REQUIRED; month начинается WIRE confirmedAt немедленно. Возврат всех 5 не отзывает 20, не меняет endAt и не выключает autopay.
7. Late first payment открытого creation: возврат поступления + новый invoice без продления creationDeadlineAt. Closed/cancelled creation: возврат без запуска, кроме доказанного D216. Late renewal: ручная сверка + D247. No match/ambiguous amount/date не переводится в success по таймеру.
8. Commit financial fact/allocations/coverage/version/outbox atomically в billing DB. Tenant-service получает событие идемпотентно, не доверяет UI. Callback/reconciliation могут конкурировать, уникальные ключи предотвращают второй эффект.

## L07. Refund и dispute

Refund kind: `SUBSCRIPTION_FULL` либо `UNALLOCATED_RETURN`; произвольного product partial refund нет даже для GLOBAL_ADMIN. `REQUESTED → APPROVED → SUBMITTING → CONFIRMED`; возможны REJECTED, UNKNOWN и FAILED. UNKNOWN сначала сверяется по исходной provider reference; retry того же намерения сохраняет business key. `CONFIRMED` требует внешнего подтверждённого движения, не решения оператора.

Перед отправкой под lock исходного fact проверить уже подтверждённые возвраты, chargebacks и незавершённые refund reservations. Не возмещать больше доступного остатка; незавершённый спор/неизвестный refund блокирует дополнительное денежное действие до сверки. Финансовый race refund+chargeback всегда записывает оба реальных факта, но не инициирует второй расход. По подтверждённому SUBSCRIPTION_FULL revoke только связанный coverage, disable autopay и пересчитать причины; старый/дублирующий payment не отзывает независимую действующую подписку.

| Подтверждённый исход спора | Доступ | Автосписание |
| --- | --- | --- |
| OPEN по текущему покрытию | DISPUTE READ_ONLY, прочие причины независимы | Остановить новые попытки |
| WON с сохранением денег | Снять только эту dispute reason; остаток прежнего периода, без продления | Новое согласие владельца обязательно |
| WITHDRAWN с окончательным PSP подтверждением сохранения оплаты | Как WON; заявление клиента недостаточно | Новое согласие |
| LOST + подтверждённый полный chargeback | Revoke связанный coverage; никакого дополнительного refund | Отключён |
| Частичный внешний chargeback до окончательной сверки | NEEDS_REVIEW, READ_ONLY по затронутому покрытию, неизменный ledger | Отключён |
| LOST_PARTIAL_COVERAGE_REVOKED (D251), подтверждённый окончательный частичный проигрыш | Dispute → LOST; полностью revoke только связанный coverage. Новое покрытие требует полной оплаты и нового месяца; независимый период не отзывается. Никакого дополнительного refund | Отключён до нового согласия |
| Только generic CLOSED / неподтверждённый status | Сверка, не считать оплатой | Не восстанавливать автоматически |

После окончательного частичного проигрыша убрать только закрытую dispute reason; при отсутствии другого покрытия действует NO_PAID_COVERAGE. Новая полная оплата не снимает pause/block/другой спор. Идемпотентная запись outcome требует confirmedMoneyFactId и evidence; generic CLOSED не заменяет подтверждения.

Банковский возврат (D252): first evidenceCompletedAt + 5 рабочих дней по WIRE_RETURN_ES_V1; decisionDueAt = sendDueAt, snapshot календаря/tzdb неизменяем. FeeBearer TASKA; principal не уменьшается. Retry/переназначение не запускает SLA заново, submitted не равно confirmed. Полный алгоритм — [C05](commercial-decisions.md).

## L08. Autopay, уведомления и версии тарифов

Mandate принадлежит текущему owner и его ownerVersion, tenant и provider payment method. Согласие сохраняет text/version/time/channel. Перед каждым новым списанием online проверить current owner, mandate, PSP/global flags, pause/block/dispute, invoice и существующую попытку. Гонка disable/dispatch сериализуется по operation fence: уже принятая PSP попытка сверяется, новые не запускаются. Restart/retry не создаёт второй invoice/charge. После expiry без согласия — READ_ONLY, без автоматического FREE.

Bank renewal scheduler за 7 суток (настраиваемо) создаёт один invoice и одно обязательное inbox-уведомление текущему владельцу; email доставляется отдельно с retry. Срок invoice default 7 суток — отдельный параметр, не lead time. Opt-out обычных уведомлений не отключает финансовые обязательные события.

Новая версия каталога не меняет существующий snapshot сразу. Warning anchor — durable создание обязательного inbox, не отправка email. `eligibleAt = inboxCreatedAt + configured 30 full days`. Paid effectiveAt — первая граница периода не раньше eligibleAt; FREE effectiveAt = eligibleAt. Отказ email не сдвигает дату, остаётся delivery retry. Worker на effectiveAt атомарно меняет plan version, пересчитывает limits/capabilities и сохраняет lifetime counts. Новая компания сразу получает опубликованную версию.

## L09. Capabilities и независимые ограничения

Общий evaluator получает actor/channel/grant + tenant/ACL + lifecycle + restriction set + entitlement/catalog/flag/usage versions. Сначала channel/scope/integrity, затем business exceptions GLOBAL_ADMIN, затем обычные роли и причины. Результат содержит allowed actions и reasons для каждого запрета, а не один boolean `readOnly`. Cached input не означает cached authorization; готовый owner/admin allow не хранится как общий результат компании.

Для обычных пользователей различать: content read, content write, quota cleanup, payment recovery, pause management, membership exit, account self-delete, tenant delete/transfer. Финансовый READ_ONLY оставляет явные recovery actions; owner pause/platform block применяет общий content-read flag default true. Удаление отдельных файлов при storage READ_ONLY запрещено, authorized project hard-delete разрешён. D244: owner pause допускает transfer/delete при прочих условиях, block/dispute — нет. D249: self-delete чужого участника не блокируется состоянием компаний, если нет ownership/last-admin препятствий.

## L10. Вложения, аватар и export

Upload: проверить живой project/issue и права → reservation bytes → server-generated private object key → PUT URL не более 5 минут → confirm с повторной авторизацией и HEAD pinned object version/size/type/checksum → server-side copy в immutable final key/version, затем commit usage + metadata. Поздний PUT по старой ссылке не меняет подтверждённый файл. Клиентский key/tenant/size не авторитетны. Reservation max 20 000 000 bytes per attachment; больше/не тот object → reject и cleanup. Duplicate confirm не удваивает usage. Неподтверждённый файл никогда не доступен через download.

Download: authenticated gateway streaming/range endpoint с authoritative recheck перед каждым запросом; S3 URL клиенту не передаётся. Так revoke действует и на ранее открытый download endpoint; уже переданные байты невозможно отозвать. Внутренний S3 доступ не принимает произвольный путь от клиента. Response private/no-store, заголовки attachment безопасны. Cleanup удаляет конкретные manifest keys и освобождает committed usage один раз после подтверждения удаления; ошибки повторяемы. Для длинного stream access lease максимум30s с повторной проверкой; новый GET/Range всегда начинает authoritative check.

Avatar — глобальный объект пользователя, максимум 2 000 000 bytes, отдельный namespace/лимит, не tenant attachment; replace удаляет прежний объект после успешного нового commit. Доступ/выдача профиля регулируются текущей видимостью identity.

Export: frozen tenant/filter/requester identity + mutable operation; права перепроверяются на dequeue, каждом chunk и download. Архив хранится private, содержит только разрешённые рабочие данные; financial ledger не включается в рабочий export. Потеря доступа отменяет публикацию/скачивание, manifest и файл очищаются. Обработка bounded chunks не удерживает SQL transaction на время упаковки/S3.

## L11. Удаление компании и аккаунта

Company delete: обычный owner + explicit double confirmation + purpose-bound code на подтверждённую почту либо ранее привязанный Telegram по B17; pause разрешена, block/dispute запрещены. GLOBAL_ADMIN exception — отдельный аудируемый admin action. Команда сохраняет durable tombstone и DELETING, повышает security version, закрывает новые операции, отзывает session/grants/invitations/autopay. Domain cleanup steps по tenant: project/workflow/issue/notification, attachments/export, quotas; шаг с checkpoint и идемпотентной квитанцией. Не удалять глобальный аккаунт/аватар другого контекста. DELETED только после обязательных cleanup acknowledgements; финансовые записи выводятся в retention-managed недоступный обычным tenant API архив. Backup restore всегда сверяет tombstone, не оживляет компанию.

Account self-delete: web only, purpose-bound account code отдельно от tenant code. Проверки ownership и last ACTIVE TENANT_ADMIN во всех компаниях выполняются tenant-service через глобальный account admission fence. Все команды назначения owner/admin и новой membership сверяют fence. Все leave/remove/suspend/demotion под lock компании считают оставшихся ACTIVE администраторов, исключая accounts с ADMISSION_CLOSED/REMOVING_MEMBERSHIPS. Поэтому параллельный уход второго администратора после precheck не оставит компанию без администратора. После fence — повторная проверка инвариантов; при конфликте снять fence и отказать без удаления. При успехе auth tombstone/security version, revoke sessions/PAT/OAuth, cleanup memberships/projections/avatar, отмена приглашений этого identity. Pause/block/dispute оставшихся компаний не препятствуют (D249).

Историческое авторство ссылается на stable tombstone: «Удалённый пользователь», без прежних имени/email/avatar snapshots в выдаче/поиске. Финансовые и обязательные audit records не фальсифицируются переписыванием автора; их доступ и срок — retention policy. Admin account deletion использует тот же integrity workflow: до удаления owner должен быть явно переназначен, нельзя оставить dangling owner/zero last admin. Документы после удаления — по D253 / [retention-policy.md](retention-policy.md), через verified operator case без восстановления старой web identity.

## L12. Приёмочные сценарии реализации

Обязательные группы для TAS-377/378 и каждой локальной задачи: A/B с общим пользователем; same UUID в разных tenant; revoked grant с тёплым cache; last seat/last project race; issue 49→50/50→51; rollback не расходует quota; delete/retry не делает counters отрицательными; 31 января→февраль→31 марта; `now == endAt`; PSP callback и WIRE одновременно; refund timeout + chargeback; paid→FREE→paid; pause + payment; block + D216; stale owner autopay; date-only statement у deadline; partial 12+8 и 12 без доплаты; excess 25/20/5; expiry invite; self-delete при чужом block; восстановление старого backup после tombstone.

Тесты должны проверять не только HTTP/RPC status, но и неизменность чужих данных, точное число ledger/period/outbox/usage записей. Scenario vectors хранятся в [scenarios/acceptance.json](scenarios/acceptance.json). Этот документ и валидация его структуры не утверждают прохождение продуктовых E2E.
