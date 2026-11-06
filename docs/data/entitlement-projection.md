# Versioned entitlement projection

TAS-359 supplies `ru.taska.entitlement.EntitlementProjection` and `GrpcEntitlementSource`
in `grpc-common-lib`. Billing remains authoritative; business requests never query PSP.
The source uses `EntitlementService.GetEntitlementSnapshot`, a five-second deadline,
minimum version, signed SERVICE context and a caller-supplied mTLS stub. Online
`RpcContextSecurity.Authority` must authorize its exact tenant/service/action.

Each consumer owns `taska.entitlement_projections` and append-only
`taska.entitlement_projection_events` in its own database. New migrations install and
audit FORCE RLS in tenant-service and the opt-in project/issue/workflow tenant schemas.
Applied changesets are unchanged. The stored body is the normalized binary protobuf
encoded as base64, preserving the complete safe DTO (tenant, version, plan, coverage,
validity, paid-through, limits, features, financial reasons and issued-at).

`apply(TaskaEvent)` accepts only schema 2, TENANT, billing-service,
Entitlement/EntitlementSnapshotChanged, matching tenant/scope/aggregate and a valid
complete snapshot. Mandatory `EventAuthority` verifies authenticated source/purpose;
envelope producer text alone grants no trust. The caller must install a genuinely
verified tenant context. A broker adapter acknowledges only after this Mono commits.
The event receipt and monotonic projection update are atomic. Older versions do not
replace current state. Equal versions with changed content and reused event IDs with
different bodies fail and roll back. Duplicate/old events do not renew freshness.

`resolve(READ)` calls mandatory `Access.current` on every subscription before using
commercial facts. That adapter checks current membership/role/project ACL and returns
current independent lifecycle reasons; denial must fail, and empty means unavailable.
Actor authorization is never cached. `GrpcMembershipAccess` supplies the concrete
primary membership adapter: every call performs signed mTLS BatchValidateMemberships,
checks exact tenant/user/generation, ACTIVE membership and current role, then calls
mandatory domain/channel/lifecycle `Policy` with that live snapshot. Suspension,
removal and role changes therefore do not wait for the billing TTL. Service-only
worklist jobs require their separately scoped Access authority. Pause/block reasons are unioned with financial
reasons and cannot be cleared by a payment event. Cache keys contain tenant UUID and entitlement version, capacity
is bounded and TTL must be positive and at most 30 seconds. Neither cache hits nor a
process restart extend the durable observation time. Committed events invalidate the
local cache; a generation fence prevents in-flight older reads repopulating it.

TAS-334 adds `resolveLease(Use)` alongside the compatible `resolve(Use)` API. Its
absolute `validUntil` starts at the original memory/durable observation and is
shortened by the next future valid-from, valid-until or paid-through boundary.
Reading a warm projection does not produce another full TTL. Already-active expiry
remains a denial reason rather than turning readable expired facts into a source
failure. The [versioned capability source](../security/capabilities.md#versioned-source-composition--tas-334)
uses this deadline when joining commercial data with global flags and current authority.

`resolve(NEW_RESOURCE)` always reconciles with billing, even with a warm cache.
Missing, invalid, unavailable or older-than-required results fail without an unlimited
or implicit FREE fallback. Authoritative lookup can refresh an identical version;
only a newer version changes terms. Expiration is checked against the clock on every
result, including exclusive valid-until and paid-through boundaries. An expired DTO
remains readable as commercial facts but `validNow=false` and PAYMENT_EXPIRED is added.
`View.requireNewResource()` is a conservative admission guard: invalid time or any
restriction denies. Domain capability policy (TAS-360) still owns action-specific
recovery/cleanup exceptions. Reading existing content uses its current domain ACL and
does not depend on granting new paid rights during an outage.

D251: consumers apply complete authoritative snapshots; they do not derive rights
from disputed money or subtract periods themselves. A newer payment can select an
independent full period while a replayed older revoke remains inert. PAYMENT_DISPUTE,
pause and block are independently preserved according to their owning authorities.

Activation requires the owning service to compose the projection with real `Access` (including `GrpcMembershipAccess` and current domain policy),
`EventAuthority`, `Source` and its local `TenantTransactionExecutor`, and register the
trusted billing outbox delivery adapter. There are no permissive default beans, fake
tenant identities or automatic activation of legacy RPC paths. Production identity/
broker topology and domain admission wiring remain with TAS-316/317/340/343/360.
The apply/reconciliation paths are executable and tested in isolation; shared Kafka
delivery, PSP, public REST and stage E2E are not established by these tests.

Run `python3 scripts/harness.py check --module grpc-common-lib` with Docker available.
Owner checks and test selectors are listed in the [verification matrix](../agents/verification.md).
