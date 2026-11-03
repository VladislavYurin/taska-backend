# Подписанный контекст RPC — TAS-306

Библиотека в common-lib/grpc-common-lib предоставляет opt-in протокол. Действующие сервисы подключаются в TAS-316/317; SQL executor — TAS-307. Новые типы находятся в v1/tenant-context.proto; существующий Header/UserContext сохранён.

Получатель регистрирует точное имя каждого метода и Policy(tenantRequired, mutation), RpcContextSecurity и TenantServerInterceptor. Поддерживаются unary requests (в том числе server-streaming responses); остальные методы запрещены. Server interceptor извлекает SPIFFE identity из проверенной SSLSession и допускает message в handler только после проверки JWS, полного request hash, актуальной authority и атомарного replay consume.

В обработчике обернуть reactive pipeline через RpcContextBridge.inbound(work) до смены scheduler. Внутри pipeline VerifiedTenantContext.current() возвращает проверенный контекст; отсутствие контекста завершает работу ошибкой. Класс не имеет публичного конструктора, не раскрывает wire payload в toString и хранит defensive copies.

Для вложенного вызова получить VerifiedTenantContext из Reactor, вызвать security.delegate(parent, audience, action, scopes), установить возвращённый RequestContext в исходящий protobuf и передать parent через CallOptions.withOption(RpcContextBridge.OUTBOUND, parent). TenantClientInterceptor подпишет окончательное сообщение перед transport start. Новый hop сохраняет actor/session/grant/generations/tenant/requestId, сужает scopes и получает новый jti. Deadline и cancellation исходного gRPC context передаются downstream. Бизнес-idempotency key остаётся в исходном request, не заменяется jti.

Начальная authorize(context, rpc, policy) предназначена только доверенному gateway/service adapter: Authority обязан связать claims с уже проверенным исходным credential. Наличие активной учётной записи само по себе не доказывает право выдать контекст. Подпись не заменяет доменную ACL/квоты/бизнес-ограничения.

Обязательные production providers:

- Keys: отдельные от auth JWT Ed25519 keys, kid/issuer allowlist, emergency revocation и hard TTL доверенной конфигурации. Неизвестный kid возвращает null; недоступный обязательный источник — ошибку. Private keys не покидают issuer.
- Authority: актуальные account/grant/membership, разрешённый service→RPC/action и исходные полномочия; false означает PERMISSION_DENIED, отсутствие результата/недоступность — UNAVAILABLE.
- ReplayGuard: общий для всех реплик atomic consume(issuer,jti,retainUntil). Сохранять до expiresAt+5s; consume не отменяется при error/cancel. Недоступность/пустой ответ — UNAVAILABLE. In-memory реализация подходит только тестам.
- Clock: доверенные UTC часы. Assertion TTL≤30s, skew≤5s.

Ошибки проверки credential возвращают UNAUTHENTICATED. Не логировать raw assertions, контекст и payload. Опубликованные providers не подключаются автоматически: fail-closed конфигурация и rollout реализуются последующими задачами.

Проверка: RpcContextSecurityTest — подмена сообщения/метода/tenant, authority/replay failure, конкурентный replay, narrowing/nested hop, deadline/cancellation, параллельный Reactor context. TenantTransportTest поднимает loopback gRPC с mTLS, генерирует временный CA и сертификаты через openssl, проверяет допуск и отсутствие повторных business handler вызовов. Тестовые ключи остаются в @TempDir.
