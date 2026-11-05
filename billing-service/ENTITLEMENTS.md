# Entitlement snapshots — TAS-319

The billing database owns immutable financial rights derived from its subscription/GRANTED coverage ledger. No read calls a PSP. Gateway REST, tenant session/service trust activation (TAS-316/317), consumer projection/cache (TAS-359), general capabilities (TAS-360), and outbox delivery workers remain separate tasks.

## Contracts and access

`BillingService.GetEntitlement` implements the blueprint's safe tenant-member response. Its mandatory `EntitlementStore.ReadAuthority` checks current active membership or explicitly scoped administrative access; the existing `FinancialReadAuthority` continues to protect subscription/invoice documents for the current owner/admin. A member can read limits/restrictions without gaining access to invoices or private buyer/seller/payment data. Global role alone grants nothing.

`EntitlementService.GetEntitlementSnapshot` is an internal SERVICE lookup with tenant/action/read scope, explicit issuer allowlist, the same mandatory online authority and optional canonical positive `minimum_version`. It never returns a version below that minimum; an unavailable/newer generation yields UNAVAILABLE/ENTITLEMENT_STALE. Missing subscription is NOT_FOUND, never a default FREE/unlimited response. Missing authority is UNAVAILABLE. Missing/untrusted signatures are rejected before application handlers by the shared mTLS/JWS interceptors; both new methods are registered in the production policy map and use the Reactor bridge.

The source contracts are [billing-catalog.proto](../grpc-common-lib/src/main/proto/v1/billing-catalog.proto) and [entitlement.proto](../grpc-common-lib/src/main/proto/v1/entitlement.proto). Blueprint fields 1–10 are preserved. Additive `paid_through=11` is the exclusive end of selected GRANTED coverage (possibly past); absence is not authorization. No existing REST route changes in this task.

## Transactions and validity

[0006-entitlements.sql](src/main/resources/db/changelog/0006-entitlements.sql) appends `entitlement_snapshots` with FORCE RLS, append-only app grants, immutable UPDATE/DELETE triggers, tenant+version PK, unique source operation, and tenant+period composite FK. Prior changesets are unchanged. Existing subscriptions are materialized lazily from authoritative stored terms/periods on their first authorized lookup; no fabricated coverage is migrated.

Initialization, confirmed coverage grant, supersession and final reversal append a snapshot and `EntitlementSnapshotChanged` outbox row on the exact same transaction connection as the subscription, receipt and existing domain event. A subscription row lock serializes all writers and time-based refreshes. Identical command/evidence replay creates no additional version/event. A snapshot outbox failure rolls back the period, subscription change, snapshot and receipt. Snapshot versions are independent of subscription mutation versions, because time-based refresh can advance rights without a subscription command.

FREE uses the accepted immutable FREE terms, no paid period and no payment restriction. Before first payment, selected PAID terms carry PENDING_FIRST_PAYMENT and never activate FREE. Active GRANTED coverage uses that period's immutable plan/limits/features. Financial expiry/revocation carries PAYMENT_EXPIRED and does not imply FREE. Paid lifetime-task unlimited is explicit in its quota entry; a missing snapshot is not unlimited.

`valid_until` bounds an active paid snapshot by its exclusive period end and the earliest future GRANTED start, whichever is earlier. Future coverage never changes current limits before its start. At a boundary the next authorized read derives the current state, appends one new version/event under the row lock, and returns it. No scheduler is trusted to expire paid rights. Old paid versions remain immutable and cannot authorize after their validity boundary. FREE/no-current-paid snapshots may omit a business end; downstream caches must still enforce the hard bounded freshness policy of TAS-359 (at most 30 seconds), event invalidation and primary checks for mutations. Polling must not extend a cached paid allow past its stored boundary.

Final D251 loss revokes the whole exact source-linked period through `SubscriptionStore.revoke`; independent GRANTED periods remain eligible. Reordered old settlements cannot revive REVOKED coverage. A new full payment supplies a distinct full period, without carrying over the revoked remainder; autopay remains disabled. An independently recorded PAYMENT_DISPUTE restriction is retained when coverage changes; financial-case evidence and setting/clearing that independent restriction belong to its case owner. Owner pause/platform block, ACL, quota and channel policies remain independent inputs to capabilities and are never cleared or bypassed by a financial snapshot.

## Outbox and verification

Each entitlement outbox record has immutable tenant, event ID, operation ID, aggregate ID=tenant, strictly increasing aggregate version, schema version 2 and the full safe snapshot payload. Its producer is billing-service and scope TENANT; the shared tenant/type/aggregate partition-key rule must be used by the future dispatcher. Retry must publish that original record, not regenerate an ID/version or recompute rights from an old event. Consumers apply only newer versions and recheck validity even without an invalidation event; this task supplies authoritative lookup/history/outbox, not a deployed Kafka consumer.

```sh
python3 scripts/harness.py check --module billing-service
```

`EntitlementIntegrationTest` is a required nonempty report. Its isolated PostgreSQL/app LOGIN tests cover states, exact UTC expiry, concurrent refresh, future boundaries, D251/reorder, independent restriction preservation, transactional rollback, lazy legacy materialization, immutable/RLS/composite-FK history and real mTLS/JWS user/service RPCs with denied/unavailable cases. These checks do not claim external REST/PSP/Kafka delivery or shared-stage B2B availability.
