# Service identity и ротация RPC — TAS-317

Production providers подключаются через `ManagedRpcTrustAutoConfiguration` при
`taska.rpc.context.enabled=true`. Custom test providers не используются при обычном
запуске приложения. `RpcContextSecurity` подписывает Ed25519 assertion отдельно от
access JWT; `ManagedRpcPeerInterceptor` проверяет актуальный fingerprint клиентского
сертификата на каждом вызове, включая существующие TLS connections. Все listeners
требуют TLS bundle, `client-auth=REQUIRE`, `secure=true`; plaintext fallback отсутствует.
Trust IO/crypto выполняются на boundedElastic, а не на Netty event loop.

## Поставка конфигурации

Каждый процесс получает только свои read-only mounts:

| Property | Содержимое |
|---|---|
| `taska.rpc.trust.snapshot` | Общий публичный JSON snapshot keys/identities/edges |
| `taska.rpc.trust.signing-key` | Локальный приватный JSON bundle `kid`, `privateKey` (base64 PKCS8 Ed25519) |
| `taska.rpc.tls.ca-certificate` | PEM доверенных CA |
| `taska.rpc.tls.certificate` | Собственный PEM certificate chain |
| `taska.rpc.tls.private-key` | Собственный PEM TLS private key |
| `taska.rpc.trust.auth-host/auth-port` | Приватный auth primary endpoint, default `auth-service:9091` |
| `taska.rpc.trust.tenant-host/tenant-port` | Для auth: приватный tenant primary, default `tenant-service:9099` |

Не монтировать каталог с ключами других служб. Access JWT key, refresh receipt key и
RPC signing key имеют разные назначения. Приватные keys/bundles выдаёт оператор через
secret storage; они не хранятся в Git, командах/логах CI или публичных отчётах.
`SigningMaterial.toString()` скрывает содержимое. Auth также требует реальные ключи
`taska.b2b.sessions.*` из [контракта сессий](../b2b/auth-sessions-implementation.md).

Snapshot соответствует Java records `ManagedRpcTrust.Snapshot/Service/Key/Edge`:

- `version=1`, монотонный `generation`, точный `environment=DEPLOY_ENVIRONMENT`, UTC
  `issuedAt/validUntil`. TTL не более 120 секунд; источник обновляет snapshot заранее.
  Замена атомарная, новое содержимое всегда получает новую generation. Уменьшение
  generation и изменение bytes при прежней generation запрещены в живом процессе.
- `services`: точное service name → positive `generation` и множество SHA-256 DER
  fingerprints разрешённых leaf certificates. Пустое множество отзывает identity.
  SPIFFE SAN ровно `spiffe://taska/{environment}/{service}` и проверенная CA chain
  обязательны независимо от fingerprint. `nodeId` не участвует в допуске.
- `keys`: issuer, kid, base64 X.509 Ed25519 public key, notBefore/notAfter, revoked.
  Неизвестный/отозванный kid не принимается. Private key остаётся только у issuer.
- `edges`: точные issuer/audience/full RPC/action, credential `kinds` без префикса
  `CREDENTIAL_KIND_`, допустимые scopes, purpose, serviceIdentities, tenantMode/tenants.
  Wildcard RPC отсутствуют. Все scopes вызова должны входить в **одну** разрешённую edge.
  `NONE` требует отсутствие tenant; `BOUND` — установленный tenant; `SET` дополнительно
  ограничивает его явным набором UUID. `BOUND` для SERVICE — явное операторское право
  на этот purpose в разных tenants; используйте `SET`, если нужны фиксированные tenants.

Для user credentials purpose=`USER`, serviceIdentities пусты: доверенный issuer
делегирует уже проверенную session/PAT binding. Каждый recipient проверяет primary
account auth_version, точный credential/session/grant, expiry/revocation и membership
заново через auth. Чтения также uncached; отзыв не ждёт 30-секундного TTL assertion.
Auth запрашивает `TenantMembershipService.GetMembership` с собственной SERVICE
identity, purpose=`membership`, scope=`memberships:read`, через mTLS/JWS. Этот путь
не рекурсирует через user authority. Текущие USER_OAUTH/ADMIN credentials без реализованной
primary проверки отвергаются; наличие `GLOBAL_ADMIN` этого не меняет.

Служебные purposes `provisioning`, `outbox`, `billing`, `admin`, `membership` отдельны.
`ManagedServiceContexts` создаёт начальный SERVICE context только после проверки edge.
Подписанные `service_identity` и `service_purpose` сохраняются при делегации; generation
относится к исходному service principal. Edge разрешает конкретные serviceIdentities,
поэтому смена промежуточного issuer не стирает источник полномочий. SERVICE не становится
пользователем или admin grant; actor/session/credential/grant/admin поля запрещены.
Доменные jobs по-прежнему подключают собственные узкие adapters; эта конфигурация не
запускает неподключённые workers и не даёт им общий admin bypass.

Файл отсутствует, повреждён, просрочен или источник primary authority/replay недоступен →
`UNAVAILABLE` до бизнес-обработчика. Last-known-good после TTL не используется. Готовность
зависит от доставки актуального snapshot **всем** репликам: подтверждайте generation
при rollout, исключайте stale replicas из маршрутизации. Process restart не заменяет
контроль anti-rollback в доверенном внешнем источнике конфигурации.

## Общая replay-защита

Приватный `RpcTrustControlService` в auth доступен только текущей mTLS identity.
Проверку claims может запросить только issuer или recipient этой edge; consume —
только сам recipient. Control channel не использует business assertion interceptor,
поэтому его создание не зависит циклически от `RpcContextSecurity`.

`taska.rpc_assertion_replay` в auth primary имеет PK `(recipient,issuer,assertion_id)`.
Все реплики одного recipient используют ту же таблицу. Consume — отдельное autocommit
соединение; откат/cancellation бизнес-транзакции не возвращают использованный jti.
После начала consume cancellation подписчика не отменяет его SQL completion. Повтор
получает отказ; transport retry создаёт новый jti, сохраняя business idempotency key.
Запись сохраняется до exp+5s с дополнительным 5-секундным запасом для разницы часов
auth primary и recipient; bounded cleanup удаляет только истёкшие записи. Запас относится
к хранению nonce, а границы exp−iat и receiver skew остаются прежними. Auth/replay
pool должен иметь запас соединений для control plane отдельно от приостановленных
business transactions. Отказ auth не обходится локальной in-memory таблицей.

## Ротация и emergency revoke

1. Опубликовать новый public key вместе со старым во всех recipient snapshots; проверить
   generation/доставку. Новый key уже находится в своём notBefore/notAfter.
2. Для каждого issuer replica атомарно заменить **локальный** signing JSON bundle.
   Старый и новый bundle могут сосуществовать на разных replicas в согласованном overlap.
   Bundle сверяется с public key перед подписью; kid/private mismatch закрывает допуск.
3. После подтверждения переключения и окончания всех in-flight assertions (TTL≤30s,
   skew≤5s) удалить/отозвать старый kid во всех snapshots. Не использовать clock skew
   как дополнительный срок выдачи credential.
4. При компрометации сразу поставить revoked и исключить stale replicas; отозвать leaf
   fingerprint и заменить TLS material/каналы. Receiver проверяет client fingerprint
   на каждом RPC, поэтому прежняя TLS connection не обходит revoke. Перезапуск/замена
   issuer channels нужны для обновления собственной TLS identity. CA rotation проводится
   отдельно с контролируемым overlap цепочек и удалением старой CA после drain.

## Deployment и проверка

Business gRPC остаётся только во внутренней Docker network: никаких host publications,
публичных Traefik TCP/gRPC routes или обходных plaintext listeners. В stage inspect
проверяются фактические PortBindings и router targets, а не только source Compose.
Включение требует [SQL cutover](trusted-rpc-context.md#legacy-sql-cutover): отдельные
runtime роли, остановку старых replicas и согласованное владение существующими NULL rows.
Default tenant, автоматический backfill и mixed legacy/trusted rollout запрещены.
Merge кода не является подтверждением включения trusted mode на общем стенде.

Локальная приёмка на настоящих production providers и packaged applications:

```bash
python3 scripts/harness/trusted_rpc_e2e.py
# После прерванного собственного прогона (путь выдаётся при запуске):
python3 scripts/harness/trusted_rpc_e2e.py --cleanup target/harness-e2e/<RUN>/state.json
```

Нужны Java 21+, OpenSSL для временных RSA TLS сертификатов, Docker/Compose и локальная
Maven cache/network. Ed25519 fixture keys создаёт Java source launcher, поэтому старый
macOS LibreSSL не подменяет Ed25519 другим алгоритмом. Runner использует отдельные БД,
runtime RLS roles, CA/key каждой службы, signed run-state и ownership-checked cleanup.
Никакие shared данные/ключи не меняются. Отчёты и diagnostics остаются в ignored target.

Сценарий: REST → real auth/session → online tenant membership → signed nested
project/issue/workflow RPC → domain + outbox transaction → Kafka, два tenant с одним
actor/key/idempotency key, foreign denial, ротация/отзыв, membership revoke, trust outage
и неизменность записей при отказе. `ManagedRpcTrustTest` отдельно проверяет overlap и
отзыв старого assertion; `AuthRpcTrustIntegrationTest` — concurrent consume двух реплик,
сохранность consume после rollback и revoke уже открытой mTLS connection. Эти проверки
не заменяют stage rollout/cutover и downstream feature acceptance будущих B2B задач.
