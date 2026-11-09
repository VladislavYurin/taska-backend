# Подписанный контекст RPC

Библиотека в common-lib/grpc-common-lib предоставляет opt-in протокол. Подключение legacy RPC описано ниже (TAS-316), production trust providers и включение — TAS-317; SQL executor — TAS-307. Новые типы находятся в v1/tenant-context.proto; существующий Header/UserContext сохранён.

Получатель регистрирует точное имя каждого метода и Policy(tenantRequired, mutation), RpcContextSecurity и TenantServerInterceptor. Поддерживаются unary requests (в том числе server-streaming responses); остальные методы запрещены. Server interceptor извлекает SPIFFE identity из проверенной SSLSession и допускает message в handler только после проверки JWS, полного request hash, актуальной authority и атомарного replay consume.

В обработчике обернуть reactive pipeline через RpcContextBridge.inbound(work) до смены scheduler. Внутри pipeline VerifiedTenantContext.current() возвращает проверенный контекст; отсутствие контекста завершает работу ошибкой. Класс не имеет публичного конструктора, не раскрывает wire payload в toString и хранит defensive copies.

Для вложенного вызова получить VerifiedTenantContext из Reactor, вызвать security.delegate(parent, audience, action, scopes), установить возвращённый RequestContext в исходящий protobuf и передать parent через CallOptions.withOption(RpcContextBridge.OUTBOUND, parent). TenantClientInterceptor подпишет окончательное сообщение перед transport start. Новый hop сохраняет actor/session/grant/generations/tenant/requestId, сужает scopes и получает новый jti. Deadline и cancellation исходного gRPC context передаются downstream. Бизнес-idempotency key остаётся в исходном request, не заменяется jti.

Начальная authorize(context, rpc, policy) предназначена только доверенному gateway/service adapter: Authority обязан связать claims с уже проверенным исходным credential. Наличие активной учётной записи само по себе не доказывает право выдать контекст. Подпись не заменяет доменную ACL/квоты/бизнес-ограничения.

Обязательные production providers:

- Keys: отдельные от auth JWT Ed25519 keys, kid/issuer allowlist, emergency revocation и hard TTL доверенной конфигурации. Неизвестный kid возвращает null; недоступный обязательный источник — ошибку. Private keys не покидают issuer.
- Authority: актуальные account/grant/membership, разрешённый service→RPC/action и исходные полномочия; false означает PERMISSION_DENIED, отсутствие результата/недоступность — UNAVAILABLE.
- ReplayGuard: общий для всех реплик atomic consume(issuer,jti,retainUntil). Сохранять до expiresAt+5s; consume не отменяется при error/cancel. Недоступность/пустой ответ — UNAVAILABLE. In-memory реализация подходит только тестам.
- Clock: доверенные UTC часы. Assertion TTL (exp−iat) ≤30s; receiver clock skew≤5s: now<exp+5s, на границе отказ. Skew компенсирует разницу часов, не увеличивает exp−iat. Future iat>now+5s запрещён.

Ошибки проверки credential возвращают UNAUTHENTICATED. Не логировать raw assertions, контекст и payload. Сам протокол не создаёт production trust providers. Их поставка, ротация и проверка развёрнутого контура относятся к TAS-317.

Проверка: RpcContextSecurityTest — подмена сообщения/метода/tenant, authority/replay failure, конкурентный replay, narrowing/nested hop, deadline/cancellation, параллельный Reactor context. TenantTransportTest поднимает loopback gRPC с mTLS, генерирует временный CA и сертификаты через openssl, проверяет допуск и отсутствие повторных business handler вызовов. Тестовые ключи остаются в @TempDir.


## Подключение legacy API — TAS-316

`taska.rpc.context.enabled=true` включает `GatewayContextFactory`, реестр
`grpc-common-lib/src/main/resources/security/rpc-route-policies.json` и межсервисные
interceptors. Реестр содержит явные политики 131 защищённого legacy RPC из TAS-303;
новые B2B RPC сохраняют собственные policies, публичные auth/health — отдельные границы.
Legacy request messages получают additive `RequestContext context = 100`, номера
`Header`/body и прежних полей сохранены. Сгенерированные Java stubs не редактируются.

Gateway обращается к приватному `GatewayCredentialService` auth-service через mTLS.
Bootstrap RPC доступен только identity `spiffe://taska/{environment}/api-gateway`.
Auth проверяет Ed25519 access JWT (`taska-auth` / `taska-api`), durable web/tenant
session, auth generation и актуальное membership, либо привязанный PAT/grant.
Legacy HMAC access JWT и PAT без tenant-binding не повышаются до trusted context.
`X-Tenant-Id`, URL, protobuf actor/tenant и обычный `Header` не выдают полномочий.
Несовпадающие идентификаторы отклоняются, `GLOBAL_ADMIN` не даёт project ACL bypass.

Фабрика устанавливает настоящий `VerifiedTenantContext` в Reactor pipeline.
`GrpcExceptionHandler.withErrorHandling` переносит проверенный inbound transport
context до смены scheduler; исходящие legacy clients используют
`RpcContextBridge.outbound(() -> stub.rpc(...))`. Supplier создаёт новый generated
publisher для каждой подписки/retry. Вложенные вызовы сохраняют requestId и principal,
передают deadline/cancellation, получают новые assertion IDs и не расширяют scopes.

Без flag сохраняется старый режим транспорта. При включении отсутствие обязательных
providers не приводит к plaintext fallback. `TrustedRpcTlsConfiguration` требует пути
`taska.rpc.tls.ca-certificate`, `taska.rpc.tls.certificate`, `taska.rpc.tls.private-key`;
получатель отдельно требует TLS listener с обязательным client certificate.
`FileMtlsRpcChannels` проверяет цепочку CA и точный SPIFFE SAN получателя, включая
`DEPLOY_ENVIRONMENT`. Настройка серверного TLS и секретов — контракт TAS-317.

### Legacy SQL cutover

Project/issue/workflow legacy repositories используют tenant RLS и transaction-local
`app.tenant_id` / `app.actor_id`, взятые только из verified RPC. Внешняя reactive
транзакция охватывает обработчик и domain/outbox write; independently committed saga-транзакции получают тот же verified scope через transaction manager hook. Ресурсные требования учитывают отдельное соединение при приостановке внешней транзакции.
Триггер назначает tenant новым строкам и отвергает смену tenant/ссылки на чужие строки.
Общие workflow templates доступны только для чтения. Фоновые outbox publishers
сохраняют tenant из записи и не создают контекст пользователя.

Включение требует отдельную runtime LOGIN-роль `NOINHERIT`, без superuser/BYPASSRLS,
ownership, memberships и прав управления ролями/БД. Migration credential отдельный.
Для runtime роли оператор задаёт `app.rpc_scope_enforced=true` до запуска новых
соединений. Startup guard сверяет настройку роли и flag. Обычный runtime SQL без
verified transaction не видит tenant rows; scope не остаётся в pool после транзакции.
После появления scoped rows возврат в legacy mode запрещён startup guard.

Миграции не назначают tenant старым данным: NULL остаётся нераспределённым и скрывается
после cutover. Нужен согласованный операторский план владельцев/переноса старых данных
и остановка старых реплик; mixed rollout со старыми открытыми соединениями не допускается.
Эти изменения не заменяют native tenant-storage API задач TAS-340/342/343, их квоты,
capabilities и business policies. Включение на общем стенде без проверенного плана и
production providers не подтверждено самим наличием transport кода.

Проверки: `LegacyRpcTransportTest` (нативный mTLS, SPIFFE pin, automatic context,
actor mismatch, revoke/outage, scheduler, concurrent tenants, protobuf inventory),
`GatewayCredentialResolverIntegrationTest` (настоящие JWT/PAT и PostgreSQL),
`TrustedGatewayContextTest`, `TrustedProjectOutboxIntegrationTest` (Spring gRPC/TLS,
непривилегированная PostgreSQL роль, domain/outbox, tenant reads и Kafka consumer contract).
Это локальные изолированные проверки; deploy/stage acceptance фиксируется отдельно.

Trusted tenant directory не направляется в legacy глобальный SQL поиск: до подключения scoped adapter TAS-321 возвращается `UNAVAILABLE` / `TENANT_DIRECTORY_NOT_READY`. Это отдельная готовность доменного маршрута, а не источник полномочий из `GLOBAL_ADMIN`. Новый транспорт не включает ещё не реализованные native B2B API.
