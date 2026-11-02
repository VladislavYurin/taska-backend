# Матрица доступа и угрозы изоляции — TAS-304

Основание: [D01–D249, Confluence v39](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880), B02/B03/B07/B12/B14–B18; срез кода `af7ab57ead44304b89bbd47dabeeba45b761ce16`. **Документ задаёт целевую реализацию, а не текущую гарантию backend.** Сопутствующие артефакты: [access-matrix.json](inventory/access-matrix.json) — 93 action definitions, [async-and-files.md](async-and-files.md) и [реестр источников](inventory/async-paths.json). REST/proto/TenantContext описываются TAS-305/468/470/471; здесь определены решения, которые все каналы обязаны применять одинаково.

## 1. Субъекты и эффективные роли

`OWNER` — отношение единственного владельца к tenant, не глобальная роль. Owner одновременно ACTIVE TENANT_ADMIN. `TENANT_ADMIN` получает эффективный project ADMIN во **всех** проектах своего tenant: закрытых, архивных и без explicit project membership. Проектные ADMIN/MEMBER/VIEWER действуют только при ACTIVE membership компании и соответствующем project membership. Suspended/rejoined пользователь не возвращает старые проектные права/assignments; бывший suspended admin активируется MEMBER.

GLOBAL_ADMIN имеет полный бизнес-доступ без членства/owner approval: обход квот, flags, финансового READ_ONLY, owner pause, platform block и необходимости owner для финансовых операций. Запись увеличивает фактический usage; изменение задачи не делает счёт PAID и не снимает ограничения. Техническая целостность, достоверный actor, версии, идемпотентность, secret protection и поддерживаемость операции сохраняются. Не вводится support session. Project move и partial subscription refund отсутствуют в продукте, административная роль их не создаёт.

Применение GLOBAL_ADMIN зависит от предъявленного credential:

| Credential / канал | Область и требование |
| --- | --- |
| Обычная web session | Проверенный global account; tenant выбирается отдельной server-side операцией, access/refresh A независимы от B. GLOBAL_ADMIN web требует завершённого email MFA |
| USER_PAT / USER_OAUTH | Ровно один tenant; scopes read/write + текущий actor ACL + flags/lifecycle. Роль GLOBAL_ADMIN в account не превращает этот credential в административный |
| ADMIN_PAT / ADMIN_OAUTH | Текущая GLOBAL_ADMIN + явно ADMIN grant + `scopeKind=TENANT_SET` с непустыми tenant IDs либо явно `PLATFORM`; `accessLevel=READ/MODIFY`. Scope/level никогда не bypass ролью |
| MCP | Наследует предъявленный USER/ADMIN PAT/OAuth. MCP-session не переносит tenant/ACL из прежнего invocation; каждый tool call проверяется заново |
| Service worker | Allowlisted service identity и purpose-bound durable operation/lease. Worker не является GLOBAL_ADMIN; не имеет произвольного business ACL |

`TENANT_SET=[]` и отсутствующий scope не означают PLATFORM. Scope проверяется до чтения ID, поиска, batch, totals, admin table catalog и SQL. `manageGlobalAccounts=true` отображается в permission `accounts:manage`; одновременно обязательны PLATFORM+MODIFY+актуальная GLOBAL_ADMIN. Только так интеграция блокирует глобальный account, меняет global role или удаляет account (D226). Глобальные настройки/каталог также требуют PLATFORM; выбранных компаний недостаточно.

Выдача/расширение admin grant — исключительно полноценная GLOBAL_ADMIN web session после MFA, с явным выбором компаний/PLATFORM, level и дополнительного account right. Refresh, смена tenant в web, повтор OAuth consent для другого tenant и продолжение MCP-session не меняют существующий scope. Уменьшение/отзыв grant увеличивает grantVersion; online validation и TTL из §4 ограничивают старые права.

## 2. Матрица бизнес-действий

`O` owner; `T` TENANT_ADMIN; `A` project ADMIN; `M` MEMBER; `V` VIEWER. Для O/T project membership не нужен. Везде действуют resource scope и lifecycle. GA administrative path имеет полный бизнес-bypass после проверки §1; user integrations GA такой колонки не наследуют.

| Действия | Обычные роли / дополнительное условие |
| --- | --- |
| Список своих компаний / tenant status | Активный account; USER_PAT/OAuth видит только свой tenant, web — разрешённые memberships |
| Создать компанию | Глобальная web session; выбор FREE/paid и eligibility; USER_PAT/OAuth выбранной компании не создаёт новую |
| Переименовать / pause / resume компании | Только O; resume снимает только собственную паузу |
| Список участников / directory | O/T/A/M/V активной компании; профиль не открывает проектный доступ |
| Пригласить, resend, выдать/снять TA, suspend/reactivate/remove | O/T; owner/last ACTIVE TA защищены; seat/reservation атомарны |
| Принять приглашение | Адресат с подтверждённым email и purpose token; membership ещё отсутствует, поэтому generic ACTIVE membership guard не применяется до acceptance |
| Самостоятельно выйти | Self, кроме O/last ACTIVE TA; teardown остальных связей по generation |
| Создать проект | O/T; quota/flag admission |
| Читать проект, задачи, поиск, board, историю, comments/worklogs/labels/watchers/links/files | O/T/A/M/V в доступном проекте; filtered aggregates до page/count/window |
| Настроить проект/workflow/types/labels; управлять проектными участниками/watchers | O/T/A; target user ACTIVE same tenant; flags только для реально настраиваемой функции |
| Архивировать/восстановить проект | O/T/A; archived project нельзя обычным способом редактировать; archive не освобождает quota |
| Безвозвратно удалить проект | O/T/A в обычном состоянии; восстановительное удаление при финансовом READ_ONLY — O/T; confirmed lifecycle до release quota |
| Issue create/update/delete/assign/transition, comments create, attach labels, links, self watcher | O/T/A/M; assign target ACTIVE membership и project access; link проверяет оба конца |
| Comment update/delete | Для обычных O/T/A/M только собственный comment. GA administrative path может управлять поддерживаемыми данными с честным actor |
| Worklog create/update/delete | M только свои; O/T/A управляют чужими по существующему manage-worklog правилу |
| Attachment upload/confirm | O/T/A/M + owned reservation/manifest; actual size/type server validation |
| Attachment delete | M только свои; O/T/A любые в своём проекте. Storage READ_ONLY не создаёт исключения для удаления вложения |
| Export create/download | O/T все проекты; A/M/V только свои доступные проекты. Финансы/credentials исключены; ACL проверяется при build и download |
| Inbox/read preferences/mark read | Только свои; payload access проверяется отдельно; личный opt-out не выключает mandatory owner notices |
| Подписка/платежи/счета/финансовые документы/refund request/autopay | Среди обычных ролей только O. T не получает финансовые права от effective project ADMIN |
| Передача владения | O, paid active coverage, flag, ACTIVE recipient same tenant, explicit confirmation; no recipient acceptance и no extra OTP D248 |
| Удаление компании | O; две явные проверки намерения + отдельный purpose code; D244 restrictions ниже |
| Global profile/avatar/password/email/account self-delete/PAT create | Только собственная ordinary web session (в том числе social web login). USER_PAT/OAuth/MCP запрещены D221 |
| Global account profile/block/role/delete через admin integration | Только условия D226 из §1; нельзя заменить PLATFORM полным списком tenant IDs |
| Generic admin queries/audit/outbox retry/global flags | Только admin path; read/query — READ, retry/настройка — MODIFY; internal admin audit не виден tenant ролям |

Полный JSON различает READ/MODIFY/PROTOCOL по эффекту, а не HTTP-методу. POST search — READ; export job creation и mark-read — MODIFY; служебный аудит успешного чтения не превращает его в business MODIFY. Token refresh/revoke — отдельный protocol, не выдача PAT. Обычная user web session разрешает безопасный просмотр/отзыв своего PAT при выключенном PAT flag.

Текущий код и изменения: `IssueProperties.allowedRoles` — база project role capabilities; comments сейчас используют commentRoles для чтения (ADMIN/MEMBER), поэтому целевое V чтение требует отдельной read policy. Обычное авторство comment update/delete сохраняется. Существующий admin displayName endpoint использует PLATFORM+MODIFY+accounts:manage для интеграций, полноценную GA web session для web. `ProjectServiceImpl.getProjectContext` сейчас требует explicit project membership и не даёт GA вне проекта — это current gap, а не целевой запрет. `EndpointAccessPolicy` прежнего PAT этапа запрещает admin token path; TAS-499 заменяет его явными admin grants. Current `UserInitializer` создаёт demo USER без отдельного default=false флага: TAS-369 исправляет bootstrap, TAS-481 подключает verified email первого GA.

## 3. Lifecycle: независимые причины и исключения

Храним независимые facts/lifecycle flags, а не один enum, который оплата перезаписывает. `reasons[]` содержит только актуальные ограничения уже авторизованного scope. Порядок вывода стабильный: PLATFORM_BLOCK, OWNER_PAUSE, PAYMENT_DISPUTE, PENDING_FIRST_PAYMENT, PAYMENT_EXPIRED, OVER_QUOTA. Он не означает, что первая причина стирает остальные. `reason` конкретной операции выбирается из первой реально блокирующей проверки, не просто из первого элемента массива.

| Policy group | ACTIVE | PAYMENT_EXPIRED / OVER_QUOTA | OWNER_PAUSE | PLATFORM_BLOCK | PAYMENT_DISPUTE | PENDING_FIRST_PAYMENT |
| --- | --- | --- | --- | --- | --- | --- |
| CONTENT_READ / EXPORT_DOWNLOAD | ACL | ACL | ACL если глобальный read flag=true | ACL если read flag=true | ACL | Запрещено |
| NORMAL shared business write | ACL/flags/quotas | Запрещено | Запрещено | Запрещено | Запрещено | Запрещено |
| RECOVERY: O/T remove member, revoke invite, delete project | ACL | Разрешено | Запрещено | Запрещено | Запрещено | Только revoke уже существующего служебного residue; новых memberships нет |
| INVITE_ACCEPT | Действующий token+reserved seat | Разрешено по старому reservation | Запрещено | Запрещено | Запрещено | Запрещено |
| TRANSFER | O + paid coverage + flag | Только если покрытие ещё действующее и нет других обычных запретов; quota READ_ONLY блокирует ordinary transfer | Разрешено D244, если все независимые условия есть | Запрещено | Запрещено | Запрещено |
| COMPANY_DELETE | O + confirmation/code | Разрешено | Разрешено D244 | Запрещено D244 | Запрещено D244 | Использовать cancel_creation D217; full deletion не подменяет lifecycle |
| LEAVE | Self + not owner/last TA | Разрешено | Разрешено | Разрешено | Разрешено | Если membership существует, тот же guard |
| ACCOUNT / ACCOUNT_DELETE | Self web + global guards | Те же global guards | Разрешено D249 | Разрешено D249 | Разрешено D249 | Owner guard всё ещё действует |
| CONTROL/FINANCE_READ | По текущей роли | По роли | Owner получает status/разрешённые обращения | Owner получает status/разрешённые обращения | Owner получает status/обращения | Owner billing/status/cancel only |
| PAYMENT / FINANCE_RECOVERY | Только O | Разрешённые способы восстановления | No new autopay; разрешённые финансовые обращения; resume прежде обычного возобновления | No new autopay; ручные финансовые обращения | No new autopay; финансовые обращения и сверка фактов | Pay/status/cancel по D208 |
| EXPORT_CREATE | Read ACL + export flag | Разрешено | Только при разрешённом чтении | Только при разрешённом чтении | Read ACL | Запрещено |
| PERSONAL_INBOX | Self | Разрешено | Self metadata без закрытого project content | То же | Self | Только control/security/finance доступные сообщения |
| ADMIN | После административной аутентификации и scope/level | Бизнес-bypass | Бизнес-bypass | Бизнес-bypass | Бизнес-bypass | Бизнес-bypass |

TRANSFER строка не создаёт новое исключение из всех видов READ_ONLY: D244 явно отменяет блокировку самой owner pause; active paid coverage/flag и остальные действующие ограничения проверяются отдельно. Удаление компании допускается при неоплате/квотах и owner pause; блокировка или dispute запрещает owner deletion даже если read flag=true. **Удаление глобального account D249 отдельно:** если нигде не owner и не last ACTIVE TA, company pause/block/dispute не препятствует self-delete; связанные memberships автоматически прекращаются без удаления компаний/финансовых фактов.

Account deletion и last-admin/owner guards проверяются по **всем** memberships, не только выбранному tenant, до выдачи challenge и повторно под соответствующими locks при commit. tenant-service ставит global account admission fence; все операции назначения owner/admin, новой membership и нового tenant проверяют этот fence, поэтому новые обязанности не возникают после global precheck. Purpose ACCOUNT_DELETE не принимается для TENANT_DELETE и наоборот; code связан с account/target/version/expiry/single-use. Race создания нового owned tenant или ухода другого admin до commit отклоняет исполнение без частичного удаления. Новый account с тем же email не восстанавливает старое авторство или membership.

Owner pause, platform block и dispute останавливают **новые** autopay attempts; уже принятые attempts сверяются до terminal ledger fact. Их сохранение не означает новый платёж. Owner financial calls, для которых settlement процедура ещё O04, возвращают POLICY_NOT_CONFIGURED для конкретной команды, но не блокируют чтение/сохранение финансового факта и независимые действия. O08 (PSP) и O09 (retention) аналогично локализованы.

## 4. Детерминированный evaluator и ошибки

Decision pipeline для обычной новой команды:

1. Проверить credential signature/issuer/audience/expiry/revocation/account status, mTLS service identity и origin channel. Неаутентифицированный запрос не выполняет resource lookup.
2. Проверить channel restrictions, scopes, admin grant kind/level/tenant set/extra permission. USER token не может добавить `admin=true`; наличие GA в profile не bypass. Нет implicit tenant fallback.
3. Разрешить resource только в trusted tenant и validated parent chain. Чужой ID/ключ/вложенный object indistinguishable от отсутствующего. Batch содержит явный foreign ID — атомарный отказ; search filters ограничивают result set, без чужих counts.
4. Проверить актуальное ACTIVE membership, effective role, ownership/self-author constraint и domain lifecycle. Для invitation acceptance/account operations/worker purpose применяются их отдельные guards, не обычный membership шаблон.
5. Вычислить capabilities по versions flags/entitlement/lifecycle; собрать independent reasons. Admin business bypass применяется здесь **после** scope и ACL origin validation, не на шагах 1–3.
6. Atomic quota admission/receipt, version/idempotency/confirmation checks. Не выделять quota до authorization; failed command не меняет resource/history/outbox/usage. Same idempotency key + different payload → conflict.
7. Domain mutation/history/outbox атомарны. Accepted durable operation re-enters по собственному receipt/fence (§5), а не как новая команда с old JWT.

Ошибки не содержат token, email чужого пользователя, private title/body, filename, raw provider response, tenant existence. `Problem.code` — стабильный код target REST; `Problem.reasons` — безопасные ограничения только уже разрешённого tenant, согласованные с public contract. Внутренний audit сохраняет полный безопасный decision code и scope без секретов.

| Случай | HTTP / gRPC | Problem.code |
| --- | --- | --- |
| Нет/недействителен credential | 401 / UNAUTHENTICATED | AUTHENTICATION_REQUIRED; известный отозванный собственный token — TOKEN_REVOKED |
| Незавершён GA MFA | 403 / PERMISSION_DENIED | MFA_REQUIRED |
| USER integration пытается D221 session-only действие | 403 / PERMISSION_DENIED | SESSION_REQUIRED |
| Недостаточен обычный scope / отсутствует admin grant / admin scope or level | 403 / PERMISSION_DENIED | SCOPE_DENIED / ADMIN_GRANT_REQUIRED / ADMIN_SCOPE_DENIED |
| Нет selected tenant | 400 / INVALID_ARGUMENT | TENANT_REQUIRED |
| Явный trusted-context tenant конфликтует с client tenant selector | 403 / PERMISSION_DENIED | TENANT_MISMATCH, без раскрытия целевого tenant |
| Чужой/несуществующий resource или parent-child mismatch | 404 / NOT_FOUND | RESOURCE_NOT_FOUND |
| Membership inactive, owner-only или project ACL failure на известном разрешённом scope | 403 / PERMISSION_DENIED | MEMBERSHIP_INACTIVE / OWNER_REQUIRED / SCOPE_DENIED |
| State / flag / quota | READ_ONLY/CAPABILITY_DISABLED: 403 / PERMISSION_DENIED; INVALID_STATE: 409 / FAILED_PRECONDITION; QUOTA_EXCEEDED: 422 / FAILED_PRECONDITION | READ_ONLY / INVALID_STATE / CAPABILITY_DISABLED / QUOTA_EXCEEDED |
| Optimistic conflict / missing If-Match / same key different payload | 412 / ABORTED; 428 / FAILED_PRECONDITION; idempotency 409 / ABORTED | VERSION_CONFLICT / IDEMPOTENCY_CONFLICT |
| Last admin / owner membership integrity | 409 / FAILED_PRECONDITION | LAST_ADMIN / OWNER_MEMBERSHIP_PROTECTED |
| Expired invite / wrong verified email | 422 / FAILED_PRECONDITION | INVITATION_EXPIRED / INVITATION_EMAIL_MISMATCH |
| Need confirmation / expired purpose code | 422 / FAILED_PRECONDITION | CONFIRMATION_REQUIRED / CONFIRMATION_EXPIRED |
| Invalid/missing input | 400 / INVALID_ARGUMENT | VALIDATION_FAILED (baseline details.reason преобразуется boundary adapter в Problem.code) |
| Dependency unavailable / missing unresolved policy | 503 / UNAVAILABLE | DEPENDENCY_UNAVAILABLE / POLICY_NOT_CONFIGURED |
| Rate limit | 429 / RESOURCE_EXHAUSTED | RATE_LIMITED + safe Retry-After |

Нормативный target error mapper — [contracts README §6](contracts/README.md); `application/problem+json`, `Problem.code/reasons/requestId`; gRPC trailer `taska-error-bin/B2bErrorDetail`. Baseline `details.reason` не становится вторым несовместимым контрактом. Имена/status mapping едины для всех сервисов. Reason priority cannot turn a known forbidden scope into a query that reveals whether B exists. Account locked/blocked credential follows auth's safe authentication error; unavailable auth is 503, never interpreted as «user has no projects» or «allow».

Technical defaults chosen for implementation (minor, no new business permissions): signed service assertion max 30s, skew max 5s, `aud` exact callee, issuer/mTLS identity allowlisted, immutable actor+session/credential/grant+scope+versions+requestId/jti; outgoing hop creates fresh assertion with same delegated restrictions. `jti` identifies attempt, not business idempotency key. Side-effect retry protection uses operationId and payload hash. Ordinary authorized read cache hard <=30s from authoritative validation; negative cache <=5s; no sliding TTL. Mutation, grant use/issuance, refresh, file GET and worker external send recheck online; dependency failure denies with 503. Resource/current global role revoke cannot be bypassed by stale JWT claim.

## 5. Threat model и точные fences

| Threat / конкретный путь | Control и fail behavior | Проверка / coding owner |
| --- | --- | --- |
| Подмена tenant/actor/role в REST header/body, gRPC Header, MCP `_meta` | Strip external trusted headers; derive from verified credential; signed audience-bound assertion + mTLS; no assertion → UNAUTHENTICATED | Direct RPC with forged role, duplicate headers, A token+B selector; TAS-306/316/317 |
| GA token confusion | Credential kind USER stays ordinary; ADMIN needs online role, scope/level/permission; wrong grant → ADMIN_*_DENIED before query | Same GA account with user/read-admin/write-admin/platform accounts-management grants; TAS-463/498/499/345 |
| IDOR across tenant or nested resource | Scoped SQL predicates + composite ownership; 404 same as nonexistent; two link ends and batch contexts checked | A issue key collides B; B comment/workflow/status/object under A URL; no state/count leakage; TAS-329/330/332/340–344/362–367 |
| SQL filter/OR/window/count bypass | Mandatory predicate outside filter AST before aggregation and pagination; allowlist identifiers + bind values + RLS | Native SQL/user filter empty/OR/unknown; admin row-id/count; TAS-307/308/309/344/346 |
| R2DBC pooled context bleed | Transaction-bound connection; SET LOCAL; finally/rollback/cancel cleanup; no runtime BYPASSRLS | A success/error/cancel/timeout then B/missing context on same physical connection; TAS-307/309 |
| Old membership/owner grants replay | Membership/account/grant/owner versions; synchronous command recheck; read hard TTL<=30s | Remove/suspend/reactivate/rejoin between auth and commit; current recipient checks; TAS-311/323/325/359 |
| Stale worker clears new assignment or replays old workflow | `(tenant,operation,resource generation)` durable receipt; compare old membership generation/assignee; CAS lease; current request IDs cannot rebind receipt | Retry revocation after new assignee assigned; APPLIED receipt + crash/reclaim; TAS-323/326/342/343 |
| Lost original user context in `.subscribe()`/scheduler | Worker creates explicit scoped context from owned journal, not ThreadLocal/Reactor caller context; caller Header only audit metadata | Every scheduled source in inventory executes with no HTTP context and correct tenant; TAS-318/326 |
| Tenant-less/forged Kafka replay | Producer topic identity registry, explicit scope kind, resource/tombstone check, scoped dedup; malformed→quarantine | Wrong producer, eventId replay with different tenant/schema/payload; no inbox mutation; TAS-318/325/326 |
| Queue leaks old recipient/private comment | Before send online recipient/project/owner/account check; body-free event payload; removed recipient SUPPRESSED | Remove access between enqueue and send; legacy body ignored; owner transfer; TAS-325/326/496 |
| OAuth consent/client cache confused deputy | Consent scope and grant immutable; PKCE/state/nonce+CSRF; metadata global/public only; browser selection does not expand refresh family | Consent A then switch web B/refresh/MCP; family replay; client metadata SSRF boundary existing controls retained; TAS-498/499 |
| Cached admin/search/meta from A returned to B | Invocation-only MCP maps; keys include tenant/scope/version; ETag auth before 304; no shared cache | Same project key different tenants, grant shrink, stale 304; TAS-334/359/497 |
| Foreign S3 key/overwrite/old signed URL | Owned manifest/reservation; pinned source version→immutable copy; authenticated GET/Range and current ACL; no raw reusable S3 URL | Confirm B key; overwrite staging post-confirm; revoke access with saved link; TAS-347/365/366/367 |
| Release quota before delete, duplicate commit or expiry race | Signed/persisted operation ownership, usage reservation CAS, successful physical deletion receipt, no negative usage | S3 timeout/crash after delete; confirm vs expire; two last-seat/byte allocations; TAS-322/324/347/460 |
| Export includes now-hidden project / expired download | Scope manifest rechecked while scanning and at download; discard unauthorized partial archive; first READY expiry immutable | ACL removal mid-build/after READY; expiry boundary/no sliding TTL; TAS-372 |
| Global account deletion erases companies or recovers identity | Global preconditions plus purpose/version/single-use; revoke account then scoped membership children; render tombstone; no email identity reuse | Last-admin race, owner added during challenge, re-register same email, D249 restricted company; TAS-461/494/496 |
| Admin generic DB path gives tenant roles platform data | Separate audience/API and checked grant, global tables PLATFORM only; masking + hidden platform audit | Tenant admin direct RPC; selected grant cross-tenant totals/global user table; TAS-345/346/350 |
| Clean-start/restore replays old unscoped events/files | Audited manifest; reject v1 scope-less events; restored IDs/generations cannot collide active namespace | Clean-start rehearsal + restoration one tenant with recipient/offboarding fences; TAS-369/370/371/374 |

Все listed threats имеют реальный вход: существующий endpoint/RPC/job, поддерживаемый целевой lifecycle или security negative variation. Для исключённых product features не создаются фиктивные endpoint и negative tests (D229).

## 6. MFA и подтверждения

Только GLOBAL_ADMIN web требует mandatory второй фактор, **только код на подтверждённую почту** (D240/D242–D245). Пароль/social login дают preauth challenge, не административную сессию. Telegram/TOTP/WebAuthn не предлагаются как альтернативный MFA этой версии. Email первого GA подключает оператор по audited recovery; недоступную verified почту меняет другой GA либо оператор, а не самообслуживание без доказательства. Обычные owner/TA не получают обязательный web MFA этим решением.

Minor challenge protocol: случайный 8-digit code, hash/HMAC at rest, absolute TTL 10min, max 5 attempts, resend cooldown 60s, max 3 resend, resend заменяет digest и не продлевает исходный deadline. Связь `(challengeId,accountId,preauthSessionId,purpose=ADMIN_LOGIN,accountSecurityVersion,expiresAt)`. Проверка/consume/attempt decrement атомарны, error не раскрывает код/почту. Issue session only after both factors and current GA role; social callback не bypass. Новые/расширенные admin grants требуют такой session; существующий admin integration request и protocol refresh не требуют нового интерактивного кода на каждый запрос.

D248: transfer, bank confirmation и refund используют действующую сессию/административный grant и явное подтверждение, без нового дополнительного OTP. Отдельные purpose codes для account/company deletion сохраняются; удаление не использует MFA challenge вместо своего подтверждения.

## 7. Критерий готовности аналитики и границы

Матрица покрывает роли и все credential kinds; source inventory покрывает current non-HTTP paths; concrete state/error/TTL/key/fence decisions заданы выше. Coding tasks должны ссылаться на action ID и source anchors, добавлять success/denial/state-preservation/concurrency checks, а не оставлять «проанализировать позже».

Текущая аналитика не доказывает работающую изоляцию: её нужно реализовать и проверить через static OpenAPI/proto parity, PostgreSQL/RLS pool IT, scoped admin, MCP/OAuth security и E2E на final head. Owner/last-admin constraints и signed scope обходить тестовым GLOBAL_ADMIN запрещено. O04/O08/O09 остаются только финансовыми/launch/retention business decisions владельца; остальные описанные механизмы готовы к coding.

Assertion детали — [contracts README §4](contracts/README.md): подписываются `rpc`, `context_sha256`, `request_sha256`; mutation `jti` consume-once, повтор получает новый assertion при том же business operationId. Presentation-колонки WEB/MCP/SERVICE_WORKER матрицы не wire enum; wire kinds — WEB_GLOBAL/WEB_TENANT/USER_PAT/USER_OAUTH/ADMIN_PAT/ADMIN_OAUTH/SERVICE.
