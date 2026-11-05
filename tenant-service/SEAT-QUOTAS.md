# Atomic seat admission (TAS-322)

`SeatQuotaStore` owns `quota_counters`, `quota_reservations` and permanent
`quota_effects` in tenant-service. All tables use audited FORCE RLS. Migration 0009
adds them without changing applied changesets. Central resources use
`scope_id=tenant_id`; this task implements SEAT. PROJECT/STORAGE_BYTES admission
belongs to TAS-324/326. TASK_LIFETIME stays authoritative in issue-service.

Usage is the count of unique ACTIVE/SUSPENDED memberships, including the owner,
plus ACTIVE unexpired invitation reservations. A global identity LOCKED state does
not delete or remove a membership and cannot free a seat. REMOVED no longer counts;
reenabling SUSPENDED does not allocate another seat. Reconciliation uses primary
membership rows rather than blindly decrementing counters.

`reserve(operation, invitation, expiresAt)` serializes on the company row, expires
local pending seat reservations, reconciles usage and checks a fresh complete billing
entitlement. Used + reserved + 1 must fit the SEAT limit. Zero is a real limit;
unlimited must be explicit. Arithmetic is bounded by BIGINT. Failures expose only
safe resource/current/limit details. A separate monotonic entitlement-version fence
prevents an older in-flight lookup from replacing already observed terms. Quota
usage never changes catalog price: this is fixed-price admission, not per-seat billing.

The immutable request hash binds tenant/operation/invitation/original expiry. A retry
returns the same reservation, including terminal state; resend cannot extend its TTL
or allocate another slot. The hash remains after `resource_id` becomes the membership
user UUID. No raw email, invitation token or financial private data is stored here.

`commit(operation, reservation, fence, user, mutation)` invokes the owning membership
mutation on the exact tenant transaction connection. That callback must compose only
its intended membership/version/outbox changes and must not subscribe independently.
The store verifies the resulting ACTIVE/SUSPENDED membership, writes a permanent
COMMIT effect, changes the reservation and reconciles usage atomically. Admission
cannot increase total usage beyond the reserved quantity. Failure rolls back member,
outbox, effect and reservation. Committed replay checks the same target and skips the
callback. An already existing member releases the spare reservation without another
used seat. No distributed transaction is involved.

L03 explicitly allows accepting an old valid invitation during financial READ_ONLY.
Commit therefore consumes the existing reservation without querying billing or
requiring a new payment. Mandatory current purpose/lifecycle authority must still
deny pause/block and invalid invitation acceptance. A billing outage cannot create a
new reservation. `release` only frees pending reservations; it never decrements used
membership seats. Expired generations cannot commit, including expiry while the membership callback is
running: the final state transition rechecks clock/fence and rolls back all work on
failure. Reconciliation samples the clock on subscription. A concurrent expiry of
another pending reservation cannot hide a callback adding multiple memberships;
both membership growth and total reserved quantity are checked. Terminal release
replays require the original generation fence. Seat expiry is local and safe
because membership commit shares this database transaction; remote PROJECT/storage
reservations require their distinct owner receipts and reconciliation.

`reconcileMemberships` can join the owning membership-removal transaction and locks
the company before recomputing actual usage. Full suspension/removal orchestration,
credential revocation and invitation delivery/acceptance routes remain TAS-323 and
TAS-335/336/339. This task supplies their executable transactional quota primitives.

All mutation calls require verified tenant/write context and mandatory online
`SeatQuotaStore.Authority`, which authorizes the exact command, current identity,
invitation purpose and lifecycle. Its `Grant.bypassLimit` must come from an explicitly
authorized scoped admin action. A GLOBAL_ADMIN role string alone grants nothing;
bypass still records actual usage, and the owning admin flow must emit its audit.
The default authority fails UNAVAILABLE until these adapters are configured.

`TenantService.GetTenantQuotas` returns the blueprint-compatible QuotaSnapshot and
SEAT QuotaUsage. It checks current ACTIVE membership before resolving safe billing
terms and again under the company lock; authorization is not cached. It reports real
used/reserved counts and explicit limit/unlimited/version. Other resource kinds are
added by their owning tasks; unknown usage is not fabricated as zero. RPC requires
signed context/mTLS through the existing tenant transport policy. Public REST wiring
remains separate. `GrpcEntitlementSource` supplies the production billing lookup when
its mTLS stub and online authority exist; unavailable dependencies have no FREE or
unlimited fallback. [`EntitlementProjection`](../docs/data/entitlement-projection.md)
defines the shared safe DTO and source contract.

Run `python3 scripts/harness.py check --module tenant-service` with Docker available.
Required `SeatQuotaIntegrationTest` uses real PostgreSQL, Liquibase and runtime RLS
roles for last-slot races, replay/conflict, expiry/release, callback/outbox rollback,
existing members, admin bypass, financial READ_ONLY acceptance, malformed/outage
snapshots and A/B isolation. `TenantMembershipIntegrationTest` also exercises the
actual quota RPC via mTLS/JWS and immediate membership revocation. These isolated
checks do not establish live invitation email, PSP, public gateway or stage E2E.
