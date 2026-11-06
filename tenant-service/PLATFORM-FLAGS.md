# Platform configuration — TAS-462

[platform-flags.proto](../grpc-common-lib/src/main/proto/v1/platform-flags.proto) implements `PlatformService.ListPlatformFlags/UpdatePlatformFlag`. Blueprint field numbers are preserved; additive fields expose effectiveAt, service owner, typed defaults and numeric bounds. Gateway REST and public effective capabilities are separate work. Legacy environment switches are not silently replaced by these B2B inputs; enforcement belongs to TAS-334/360/463 and domain integrations.

## Registry

The allowlist is seeded once by [0003-platform-flags.sql](src/main/resources/db/changelog/0003-platform-flags.sql). All are global, without tenant overrides. A service owner identifies the domain responsible for the setting; GLOBAL_ADMIN manages its value.

| Key | Default | Owner |
|---|---|---|
| READ_WHILE_RESTRICTED | true | tenant-service |
| AUTOPAY | true | billing-service |
| PAID_TENANT_TRANSFER | true | tenant-service |
| OWNER_PAUSE | true | tenant-service |
| INVITATIONS | true | tenant-service |
| PROJECT_CREATION | true | project-service |
| ATTACHMENT_UPLOAD | true | issue-service |
| EXPORT | true | issue-service |
| MCP | true | mcp-server |
| WORKFLOW_CONFIGURATION | true | workflow-service |
| TELEGRAM | true | notification-service |
| EMAIL | true | notification-service |
| ISSUE_TYPE_CONFIGURATION | true | project-service |
| PUBLIC_SIGNUP | true | auth-service |
| PERSONAL_API_TOKENS | true | auth-service |
| INBOX | true | notification-service |
| SOCIAL_SIGN_IN | true | oauth-server |
| AVATAR_UPLOAD | true | auth-service |
| WIRE_PAYMENT | true | billing-service |
| PSP_PAYMENT | true | billing-service |
| FREE_MCP | false | mcp-server |
| FREE_PERSONAL_API_TOKENS | false | auth-service |
| UNPAID_CREATION_WINDOW_SECONDS | 2592000 (30 days) | tenant-service |
| WIRE_RECONCILIATION_TARGET_SECONDS | 86400 (24 calendar hours) | billing-service |

The last two are DURATION_SECONDS, exact positive decimal int64 strings, range 1..31536000 seconds. Other entries are BOOLEAN. The duration bound is a technical validation limit of one 365-day year, not a calendar calculation. Prices/quotas remain in the billing catalog. Company name belongs to its owner setting. No company-creation, time-tracking, comment, watcher, link, label, logo or secret configuration switch is accepted. Merchant/retention/settlement policy schemas are outside this scalar registry.

## Authority and mutations

Every RPC requires verified mTLS/JWS context and online `RpcContextSecurity.Authority` validation of current GLOBAL_ADMIN with completed web MFA or a live administrative integration grant. Both calls require no tenant context, explicit PLATFORM scope, actor generation and an appropriate session/credential. List requires read and READ/MODIFY; update requires write and MODIFY. TENANT_ADMIN and ordinary USER credentials cannot use this administrative path. Missing trust providers fail closed.

An update requires a canonical UUID Idempotency-Key and the positive version returned by list (the future REST adapter maps If-Match). Exactly one typed value is accepted. Unknown keys return RESOURCE_NOT_FOUND; malformed/wrong/out-of-range values return VALIDATION_FAILED; concurrent stale versions return VERSION_CONFLICT. One global transaction advisory lock serializes administrative changes and retries. Same actor/key/hash replays the original immutable version, even after later edits; a different flag/payload/version with that intent returns IDEMPOTENCY_CONFLICT. Authorization is checked before replay.

The version row, exact version receipt and `PlatformConfigurationChanged` global outbox event commit atomically. Every non-bootstrap version is an immutable audit entry with changed_by and server time; seed version1 has no human actor. Application DML cannot edit definitions, rewrite/delete history or receipts. SQL triggers additionally validate allowlisted types/ranges. No fake tenant UUID is used for global configuration.

The event carries only key, typed value, version, exact effectiveAt and cacheMaxAgeSeconds=30. Consumers must ignore obsolete versions and use a hard, non-sliding TTL at most 30 seconds; online critical decisions must recheck authority/configuration. This registry reads PostgreSQL directly and introduces no stale local cache. TAS-334 supplies the versioned source cache and authenticated invalidation fence; the owning broker adapter must deliver committed outbox metadata, and outbox persistence alone does not prove broker delivery. Changes are immediately effective when committed; scheduling future changes is not exposed.

## Internal configuration snapshot — TAS-334

`PlatformService.GetPlatformConfigurationSnapshot` returns all24 approved public
scalar flags/properties from one SQL snapshot, a deterministic version of the complete
sorted values/versions, and `cacheMaxAgeSeconds=30`. It accepts only an authenticated,
online-authorized SERVICE identity with explicit read scope, the exact RPC action,
and no actor/tenant selector. It does not grant administrative list/update rights.
WEB_GLOBAL, tenant/user credentials and administrative PATs cannot use this internal
path. Existing administrative RPC authority remains unchanged.

The SQL allowlist excludes `MERCHANT_CONFIGURATION` and other private structured
configuration before mapping. No secrets, credentials, tenant overrides, cursor or
environment fallback is returned. `GrpcPlatformConfigurationSource` and
`PlatformConfigurationSnapshotCache` are supplied in grpc-common-lib; see
[source composition and activation](../docs/security/capabilities.md#versioned-source-composition--tas-334).
Deploy the new tenant-service before enabling callers of the additive RPC. Legacy
environment switches and public REST endpoints are not activated by these library APIs.

## Reads, deadlines and activation

List uses one SQL snapshot, key order, default50/max100. A continuation cursor authenticates purpose, current authority (including session/grant/generations), page size, all flag versions and the last key; TTL15min. A changed generation requires restarting the list. Per-request assertion IDs/timestamps do not invalidate continuation. Configure `TENANT_PLATFORM_FLAGS_CURSOR_KEY` with random Base64 of at least32bytes, shared across replicas. Missing key permits a complete one-page list, but cannot mint/accept continuation; malformed nonempty configuration fails startup. Secrets never appear in the DTO/event/cursor payload.

Paid tenant creation reads UNPAID_CREATION_WINDOW_SECONDS online on its transaction connection and stores creation_deadline_at once. Retry and later edits retain that deadline. The former taska.tenant.creation-window property is replaced by this DB input. Bank reconciliation consumers must likewise snapshot their duration/version at the original verified banking fact; no bank workflow is implemented here, and changing the registry never updates existing banking deadlines. Durations count UTC calendar seconds, including weekends, and do not themselves confirm payment or trigger a refund.

Apply migrations before the new tenant-service. Earlier migrations are unchanged; rollback to the old binary leaves additive configuration tables intact. The service remains opt-in with authoritative production trust adapters required; merging does not establish B2B gateway or shared-stage activation.

## Verification

`python3 scripts/harness.py check --module tenant-service` requires a fresh nonempty PlatformFlagsIntegrationTest report alongside the registry/membership suites. The new suite uses real PostgreSQL16, deployer/migrator roles, app LOGIN, preceding-schema upgrade/checksums, concurrency, replay/conflicts, access refusals, immutable history, atomic outbox rollback, cursor binding, stored deadline preservation and production-policy mTLS/JWS RPCs. This verifies protected backend contracts; external REST and shared-stage B2B E2E remain unavailable until their adapters are implemented.
