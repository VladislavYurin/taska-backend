# Передача владения компанией

`POST /api/v1/tenants/{tenantId}/ownership-transfer` принимает закрытое тело
`newOwnerUserId`, `confirmed: true`, UUID `Idempotency-Key` и текущую версию
компании в сильном `If-Match`. Дополнительный OTP не требуется. Полномочия
определяются действующим credential и общим capability evaluator, а не телом запроса.

Обычная передача требует текущего владельца, оплаченного покрытия, включённого
`PAID_TENANT_TRANSFER` и активного получателя в этой компании. `OWNER_PAUSE`
допускается, блокировка платформы и финансовый спор запрещают передачу. Текущий
GLOBAL_ADMIN с подтверждённой MFA проходит предусмотренный общей политикой обход
бизнес-ограничений; целостность компании и активность получателя сохраняются.
Административные PAT/OAuth закрыты до подключения первичного grant authority.

## Протокол

1. Root под блокировкой компании сохраняет `PENDING` операцию, actor/authority,
   хеш запроса, исходные owner/version и ограниченный срок жизни. Владелец ещё не меняется.
2. Billing через отдельный SERVICE `Inspect` проверяет Root receipt. Под блокировкой
   подписки повторно вычисляет фактическое покрытие и создаёт fence. Его срок не
   превышает срок Root операции и оплаченного покрытия. Незавершённый `PREPARED`
   plan change не допускает новую передачу. Изменение подписки и периодов
   блокируют SQL triggers до подтверждённого Root outcome. Истечение admission
   само по себе не снимает финансовую блокировку.
3. Root получает свежие первичные identity/configuration/entitlement, затем заново
   проверяет права, членства и версии под блокировкой компании. Одной транзакцией
   меняются owner/owner_version, версии компании и членств, роль получателя,
   receipt, audit и outbox. Прежний владелец остаётся `TENANT_ADMIN`.
4. Billing `Finish` принимает только подтверждённый Root outcome. При `COMMITTED`
   отзывает активное согласие именно прежнего owner/version, не создавая новых
   платёжных операций. При `CANCELLED` согласие сохраняется. Счета, периоды,
   финансовые факты, неизменные FREE proofs и принятые PSP попытки не переписываются.

Сетевых вызовов внутри SQL-транзакций нет. Root commit требует запас не менее
пяти секунд до истечения финансового fence; часы сервисов должны быть синхронизированы.
Admission ограничен 30 секундами и никогда не продлевается повторным Prepare.
Deferred constraint trigger повторяет deadline-проверку при COMMIT. Финансовый
fence снимается только после известного Root outcome; при недоступном Root
финансовые мутации остаются закрытыми до восстановления.

## Повторы и восстановление

Повтор с тем же actor, credential authority, ключом и хешем возвращает сохранённый
результат и завершает финансовый эффект. Повтор не требует, чтобы прежний владелец
оставался владельцем, но требует действующих identity/credential и членства.
Другие параметры с прежним ключом дают `IDEMPOTENCY_CONFLICT`. Новый запрос от
прежнего владельца не получает финансовых прав.

Worker читает ограниченную очередь routing IDs, для каждой операции получает
свежую tenant-bound SERVICE authority и повторяет `Recover`. Истёкший `PENDING`
можно только отменить; worker не переносит владение от имени отсутствующего пользователя.
`COMMITTED` повторно завершает Billing; неизвестный outcome не считается отменой.
Повторный Finish не отключает согласие нового владельца и не снимает чужой fence.

## Проверка

`TenantOwnershipTransfersIntegrationTest` и `OwnershipTransferBillingIntegrationTest`
используют PostgreSQL и реальные миграции/RLS. Внешний сценарий
`python3 scripts/harness/tenant_ownership_e2e.py` поднимает собственные auth,
gateway, Root и Billing с managed mTLS/JWS. Он проверяет REST, права, подтверждение,
повтор, отзыв согласия и сохранность финансовой истории. Его синтетические
invoice/coverage/mandate fixtures не доказывают проведение платежа у PSP.

## Managed trust и развёртывание

Новые RPC требуют точных operator-managed edges. Для private RPC нужен только
`CREDENTIAL_KIND_SERVICE`, purpose `billing` и service identity вызывающего сервиса.

| Вызывающий → получатель | RPC | action | scopes |
| --- | --- | --- | --- |
| tenant-service → billing-service | `BillingOwnershipTransferService/Prepare` | `prepareOwnershipTransfer` | `write`, `ownership:prepare` |
| tenant-service → billing-service | `BillingOwnershipTransferService/Finish` | `finishOwnershipTransfer` | `write`, `ownership:finish` |
| billing-service → tenant-service | `TenantOwnershipTransferService/Inspect` | `inspectOwnershipTransfer` | `read`, `ownership:inspect` |
| tenant-service → tenant-service | `TenantOwnershipTransferService/Recover` | `recoverOwnershipTransfer` | `write`, `ownership:recover` |

Полный префикс RPC — `ru.taska.b2b.v1.`. Публичный gateway edge на
`TenantService/TransferTenantOwnership` использует action `transferTenantOwnership`,
tenant binding и пользовательские `read`/`write` scopes. Разрешённые credential
виды определяет утверждённая общая policy; административный web target требует MFA.
Существующие primary edges конфигурации, entitlement и referenced plan также нужны.

Сначала применяются добавочные миграции и публикуются Root/Billing с private
протоколом и recovery, затем включается новый gateway маршрут с нужными trust
edges. При неполной конфигурации вызов закрыт. Документ не означает, что общий
стенд уже обновлён: PR сам не выполняет merge или deployment.
