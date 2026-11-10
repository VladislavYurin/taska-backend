# Public tenant capabilities — TAS-497

`GET /api/v1/tenants/{tenantId}/capabilities` publishes current advisory permissions for
an authenticated selected company. The public contract is the gateway OpenAPI; the
private owner is `TenantCapabilitiesService.GetTenantCapabilities` in tenant-service.
The HTTP route requires `read`, a verified tenant credential and an equal URL tenant.
Legacy/global-only sessions cannot invent a company. HTTP responses use
`Cache-Control: private, no-store`, including failures; there is no cached 304 allow.

## Actions and meaning

`CapabilityAction` in OpenAPI is the complete closed `Action.wire()` registry from the
common evaluator. In particular, member role changes use `membership.role_update`,
not a separate `membership.write` alias. This endpoint returns actions whose existing
policy scope is TENANT or TENANT_FINANCE. Project/issue/object actions need their
corresponding target and are not evaluated against a fabricated project or resource.
The transport admission action `getTenantCapabilities` is distinct from these public
business action names.

`allowed` is current permission to initiate the corresponding flow. The capability is
not a reusable authorization receipt. A mutation still checks its actual target,
quota reservation, ownership/last-admin protections, version, purpose confirmation,
financial evidence, provider readiness and all current policy at its own acceptance
boundary. No future target, confirmation, operation receipt or administrative grant
is manufactured by this read. Self-leave includes the actual current owner/last-admin
protection. Payment without a selected, verified payment channel remains unavailable.

## Current authority and sources

The existing common evaluator makes every decision. No gateway/UI business matrix is
introduced. Before source reads, an uncached private auth-primary RPC validates the
exact account generation, session/PAT and grant, current scopes and membership. It
returns only actor/credential binding, current global role, completed parent-session
MFA and an actual expiration bound; no token, email, password or private payload is
returned. Only the authenticated mTLS recipient of the original request can request
these facts. SERVICE is not a user credential for this RPC.

Tenant-service reads current membership, actor version, owner and lifecycle through
its RLS runtime role. The immutable plan kind is resolved for the actual entitlement
plan version, including previously published versions withdrawn from new sales. SERVICE reads use operator-managed keys, service identity/generation,
purposes and exact allowlisted edges. The required graph is:

- gateway → tenant, `getTenantCapabilities`, user credential, selected tenant;
- tenant → auth private control, recipient identity `tenant-service`;
- tenant → billing, `getEntitlementSnapshot`, purpose `billing`, tenant-bound;
- tenant → billing, `readReferencedPlanVersion`, purpose `billing`, explicitly global;
- tenant → tenant public configuration, purpose `admin`, explicitly global.

The local configuration read still enters a genuinely managed SERVICE context and
validates its exact policy with auth primary. A caller's user scopes are retained
without widening: a read-only token cannot acquire write capabilities, while narrowing
all transport claims to read would incorrectly hide a writer's advisory permissions.
Billing's entitlement read authority also rechecks the actual managed billing purpose;
absence of managed trust remains unavailable, with no plaintext/static-key fallback.

The source composes TAS-334 `VersionedCapabilitySource`, public configuration leases
and TAS-359 entitlement projection. Every HTTP observation reconciles both primaries;
no actor decision is cached, and this route does not depend on an unauthenticated
broker invalidation adapter. New flag/entitlement versions invalidate the previous
source identity. An outage cannot reuse a warm allow or an implicit FREE tariff.
Domain command admission remains independently current.

## Metadata and leases

`policyVersion` and `flagVersion` are opaque identifiers, not integer counters. The
flag version hashes the complete sorted, versioned public configuration, including
its durations; using the maximum per-key version would miss independent changes.
`entitlementVersion` and `membershipVersion` identify their current primary facts.
`effectiveAt` describes applicable source/tenant state, while `evaluatedAt` is the
actual evaluation time; DB clock skew cannot move `effectiveAt` beyond it. Tenant
`updated_at` bounds the observed tenant snapshot (including metadata changes), not an
invented dedicated lifecycle-event timestamp. `expiresAt` is the exclusive minimum of source leases,
credential/assertion expiration and 30 seconds. A cache read never renews a lease.

`durationsSeconds` exposes only `UNPAID_CREATION_WINDOW_SECONDS` and
`WIRE_RECONCILIATION_TARGET_SECONDS`. The latter is the configured manual bank
reconciliation target, not confirmation of receipt, settlement, coverage or refund.
Merchant configuration, credential references and raw financial data are excluded.

## Activation and verification

Managed providers are wired when trusted RPC mode and provisioned managed snapshot
paths are configured. Shared-stage cutover and private material remain operator work
under [the transport contract](rpc-service-identity.md); merging this endpoint does not
activate incomplete B2B handlers. OAuth/admin credential adapters retain their own
current-authority requirements; the endpoint does not make unsupported kinds valid.

`TenantCapabilitiesIntegrationTest` checks primary SQL/RLS, A/B roles/state, version
changes, current membership before source/cache reads, bounded expiration, source
failure and foreign-target preservation. Auth control tests cover current primary
role/MFA/credential deadline and native mTLS. The gateway contract tests check the
closed action registry and public mapping.

```sh
python3 scripts/harness/capabilities_e2e.py
```

This isolated runner uses seven packaged applications, separate PostgreSQL databases
and runtime roles, own CA/service/signing keys, managed mTLS/JWS and ownership-checked
cleanup. Trust snapshots are copied before startup and atomically replaced inside the
container filesystem, avoiding host bind-mount inode replacement during reads. Runtime
trust validation, expiry and fail-closed behavior remain unchanged. It exercises public HTTP, current auth/tenant/billing/configuration, role and
credential revocation, flag versions, cross-company denial and warm-cache dependency
failure. Subscription rows are owned synthetic fixtures; this is not payment/PSP E2E
or a claim about shared-stage activation. Reports and cleanup state stay in ignored
`target/`. The printed signed state path can be supplied to the runner's `--cleanup`
after an interrupted run; credentials and material must never be copied into Git.
