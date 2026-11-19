# Design

## Context

Root владеет tenant/membership/owner_version, billing — subscriptions, coverage и mandates. L03/D244/D248 задают policy и отзыв autopay через durable событие при немедленном owner-version fence. Применённые миграции не меняются. Сетевые вызовы не выполняются внутри SQL транзакций.

## Goals / Non-Goals

Одна идемпотентная команда с текущей identity и атомарным Root effect; оплаченный период нельзя проверить устаревшим projection. Не менять paid periods/invoices/history, не переносить чужой payment method, не отправлять новый PSP charge. Не добавлять OTP или recipient acceptance.

## Decisions

1. Root сохраняет PENDING operation с request/authority binding, ожидаемыми tenant/owner versions и deadline. Под тем же tenant lock проверяются текущие actor и target membership. Признак administrative берётся из результата общего evaluator, включая tenant web GLOBAL_ADMIN/MFA, и повторно сверяется перед commit. Только исходный живой пользовательский запрос может применить передачу; worker не исполняет отложенную смену владельца.
2. Billing Prepare проверяет Root operation через private Inspect до своей SQL транзакции. Под subscription lock проверяет текущий paid entitlement и отсутствие FREE PREPARED; сохраняет ограниченный временем fence. Billing изменения subscriptions/periods сериализуются с fence и до подтверждённого завершения отклоняются без частичного изменения.
3. Root повторяет текущую identity и свои membership/restriction/version checks, затем атомарно COMMITTED owner change, admin roles, owner_version, receipt, audit и body-free outbox. Deadline проверяется непосредственно при записи с запасом clock skew; expired admission не разрешает commit; deferred constraint trigger повторяет проверку при COMMIT. Сам fence не снимается по часам: при недоступном Root он сохраняется до recovery. Target suspension/removal и restrictions используют тот же tenant lock.
4. Billing Finish читает immutable Root receipt, отзывает только mandates прежнего owner/version и отключает старый autopay без изменения renewal cancellation/history. Успешный receipt не превращается в новый финансовый grant. Повтор не повторяет effect и не отключает согласие последующего владельца.
5. Root recovery читает bounded dispatch IDs, но получает SERVICE полномочия отдельно для каждого tenant. Он отменяет только просроченный PENDING и завершает billing side effect COMMITTED/CANCELLED. Ни pending row, ни payload очереди не являются user authority. Старый public request с тем же key/hash/credential может получить исходный receipt при текущем допустимом членстве, но не применить новую передачу.
6. При неопределённом исходе SQL/RPC запись не повторяется вслепую. Billing fence неизменяем по operation, новый grant времени под тем же operation не выдаётся. Отсутствующий Root receipt не считается отменой.

## Risks / Trade-offs

Небольшое окно fence временно закрывает конкурирующие billing изменения; это технический отказ, а не изменение принятой финансовой истории. Нужны реальные PG/RLS tests, expiry/race/fault scenarios и внешний E2E с primary services. Неизвестный grant/primary/доставка закрывают вызов; recovery продолжает по сохранённым receipts. GLOBAL_ADMIN business bypass не отменяет immutable evidence, session/MFA, bounded scope или audit.
