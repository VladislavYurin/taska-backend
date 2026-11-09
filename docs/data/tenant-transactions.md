# Tenant-транзакция R2DBC — TAS-307

`TenantTransactionExecutor` из common-lib выполняет один локальный SQL unit of work с `VerifiedTenantContext` TAS-306. Контекст должен быть установлен принимающим RPC adapter; executor не принимает tenant из аргумента, HTTP header или SQL DTO.

```java
var executor = new TenantTransactionExecutor(connectionFactory);
Mono<String> result = executor.execute(db ->
    db.sql("SELECT current_setting('app.tenant_id') AS tenant")
      .map((row, metadata) -> row.get("tenant", String.class))
      .one());
// result подписывается внутри Reactor Context с VerifiedTenantContext.
```

В production callback доменный SQL также содержит явный `WHERE tenant_id=:tenant`, привязанный к проверенному контексту. Авторизация операции и project ACL выполняются до SQL. Табличные RLS policies и роли внедряются TAS-308, PostgreSQL fixture здесь проверяет только контракт executor.

На каждую подписку executor:

1. Проверяет наличие VerifiedTenantContext и canonical tenant UUID; отсутствие tenant не открывает глобальный доступ. Actor UUID, если он есть, также проверяется. Для SERVICE без human actor значение app.actor_id очищается, чужой пользователь не подставляется.
2. Начинает транзакцию через TransactionalOperator/R2dbcTransactionManager. Настройки `set_config('app.tenant_id', :tenant, true)` и `set_config('app.actor_id', :actor, true)` выполняются bind-параметрами на transaction-bound connection до callback.
3. Предоставляет scoped DatabaseClient, который обращается к той же connection через TransactionAwareConnectionFactoryProxy. Каждое получение connection требует активного frame, совпадения tenant/actor и привязанной Spring transaction. Сохранённый client без активной tenant-транзакции SQL не выполнит.
4. Возвращает единственный результат после завершения COMMIT. Для нескольких строк выполнить collectList внутри callback. Ошибка COMMIT не выдаёт предварительный результат.

Вложенный execute для той же connection factory и тех же tenant/actor продолжает существующую транзакцию, не выполняет повторный SET и не делает отдельный commit. Несколько executor instances для одной factory совместимы; transaction-aware proxy нормализуется к исходной factory. Смена tenant/actor, отсутствие проверенного контекста или попытка вложить другую factory отклоняются до callback.

Ошибка или cancellation вложенного execute помечает общую транзакцию rollback-only. Если вызывающий код поймал её, внешний execute всё равно откатывается и возвращает ABORTED вместо успеха. Не поглощать SQL-ошибки внутри одного callback: восстановление бизнес-операции выполняется снаружи execute. Чужая ambient Spring transaction не присоединяется и не получает tenant settings.

Callback должен возвращать составленный Mono SQL-операций. Detached subscribe/block, ручное управление transaction/connection, session-level SET, замена tenant settings и внешние HTTP/gRPC ожидания внутри callback нарушают контракт. ConnectionFactory пула — одна общая dependency для executor; глобальные/admin repositories используют отдельные авторизованные пути TAS-345/346.

При error выполняются rollback и release. При cancellation до завершения callback R2DBC cancellation недостаточно для остановки запроса: PostgreSQL получает CancelRequest (ожидание доставки ограничено 5s), затем физический connection закрывается и pooled handle очищается через transaction manager. Закрытое соединение не выдаётся следующему borrower; disconnect откатывает оставшуюся PostgreSQL transaction. Это также исключает поздний CancelRequest, направленный в следующий запрос на переиспользованном connection. Если commit уже начался, отмена не гарантирует отмену зафиксированной операции; вызывающий код применяет бизнес-идемпотентность.

Настройки transaction-local исчезают после commit/rollback. RLS остаётся fail-closed при missing/empty app.tenant_id. При отмене следующий borrower получает чистую connection, при обычном завершении переиспользуется существующая.

Проверка: `TenantTransactionExecutorIntegrationTest` запускается обычным Surefire в common-lib.verify. Нужен Docker. Testcontainers поднимает отдельный PostgreSQL16 с непривилегированной ролью, FORCE RLS и pool maxSize=1. Проверяются PID/transaction ID, A→B reuse, commit/rollback/ошибка commit, cancellation во время SQL и освобождение блокировок, nested authority, caught failure/rollback-only, внешняя transaction, escaped client, параллельные компании и SERVICE без actor. Рабочие БД и миграции этим PR не изменяются.

Ошибки cleanup: если physical close завершился ошибкой и rollback также не удался, close failure сохраняется как первичная причина, rollback failure добавляется в suppressed; исходная ошибка callback сохраняется TransactionalOperator в той же цепочке. TenantTransactionExecutorFailureTest воспроизводит close/rollback/application failures без реальной БД. Дополнительный PostgreSQL тест использует pool maxSize=2: cancellation tenant A не прерывает одновременно выполняющийся SQL tenant B.
