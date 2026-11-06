# Billing catalog — TAS-313

Implemented backend subset of the [B2B contracts](../docs/b2b/contracts/README.md): a separate service/database, global versioned plan catalog, draft creation, explicit publication and internal resolution of current published offers. [Tenant-scoped subscriptions and coverage TAS-314](SUBSCRIPTIONS.md) extend this catalog. [Immutable invoice issuance TAS-475](INVOICES.md) adds tenant-scoped documents and protected RPCs. [Financial ledger foundation TAS-476](LEDGER.md) adds immutable money facts/matches/allocations. [UTC subscription calendar and renewal scheduler TAS-331](CALENDAR.md) add stable renewal assignments and guarded invoice issuance. Commercial provisioning, settlement policy and PSP calls remain separate. [Versioned entitlement snapshots TAS-319](ENTITLEMENTS.md) derive financial rights from confirmed coverage. This module does not charge money or bypass settlement authority.

## Catalog and contracts

The source of truth is [billing-catalog.proto](../grpc-common-lib/src/main/proto/v1/billing-catalog.proto). Existing blueprint field numbers are preserved. `PlanVersion.kind` (field 11) and `CreatePlanVersionRequest.kind` (field 7) explicitly identify FREE/PAID; kind is stable for a plan code. This closes the TAS-501 A16 ambiguity without deriving business eligibility from a zero price.

- `ListBillingPlans`: latest published version **per code**, never drafts/history, sorted by code. Default 50, maximum 100; HMAC cursor expires after 15 minutes and binds page size/order. Publishing a newer version does not change an older published row; a continuation is a current catalog view, not a frozen historical snapshot. No tenant or buyer data appears here.
- `CreatePlanVersion`: creates an immutable-content DRAFT. A plan row lock allocates a strictly increasing version even under concurrent requests. A new code requires explicit FREE/PAID kind; changing an existing product kind is rejected.
- `PublishPlanVersion`: requires `confirmed=true`, an exact expected plan version and a new idempotent intent. Publication only sets `published_at`. The latest published version is selected by version, not publication time. Draft creation alone changes no public offer.
- `ResolvePublishedPlan`: internal lookup by immutable UUID for new tenant creation: only the latest published version of an unarchived code is selectable; historical versions, drafts and missing rows return RESOURCE_NOT_FOUND. Old period/invoice references remain valid in the database and their stored snapshots are never repriced. Only SERVICE callers `tenant-service`/`api-gateway` with `read` and `catalog:resolve` may call it. This is the catalog side of the `PublishedPlans` integration; service-identity wiring/delegation is enabled with the shared trust adapters, not by a direct cross-service database read.

Public-safe catalog metadata still travels over authenticated mTLS/JWS internally. Listing requires a verified `listBillingPlans` action with read scope (a gateway may delegate an anonymous public metadata request as SERVICE). Mutations require verified WEB_GLOBAL with session or ADMIN_PAT/ADMIN_OAUTH with credential/grant/generation, explicit PLATFORM+MODIFY, write scope, actor and no tenant scope. The mandatory online `RpcContextSecurity.Authority` must additionally validate current GLOBAL_ADMIN, completed web MFA or current administrative grant. A wire credential kind never establishes that authority. Missing trust configuration fails closed for every RPC.

Gateway REST routing, production key/JWKS/online-authority adapters and tenant provisioning activation are not enabled by this module. The target `/api/v1/billing/plans` remains a public metadata contract; the delivered transport here is gRPC, as in the preceding tenant backend tasks. Shared deployment/readiness is not inferred from local tests or a merge.

## Data and bootstrap

[Migration](src/main/resources/db/changelog/0001-catalog.sql) seeds exactly two published versions, once through Liquibase:

| Code/kind | Gross fixed company price | Period | Members | Projects | Storage bytes | Lifetime task creations/project |
|---|---|---|---|---|---|---|
| FREE | 0 EUR | NONE | 5 | 1 | 100000000 | 50 |
| PAID | 2000 EUR minor units, exponent 2 | CALENDAR_MONTH | 50 | 5 | 5000000000 | unlimited |

The paid price is tax-inclusive (D135); tax breakdown and commercial readiness are subsequent invoice work. No multiplication by seats. EUR is the base catalog currency; USD conversion belongs to invoice snapshots, not an independently priced USD catalog. Missing task limit means unlimited only when explicitly requested for PAID. Numeric fields are exact nonnegative int64 values; members/projects must be positive. The per-attachment default is 20000000 bytes in the version row; this DTO's four quota dimensions do not add a separate attachment quota enum. Capability strings are bounded, distinct and sorted; empty seed capabilities do not grant optional channels, which require the global flags/capability evaluator.

`catalog_plans`/`catalog_plan_versions` are global nonpersonal configuration. Content is immutable from creation; publication is the only permitted update and published rows cannot be updated/deleted. Old invoice/period references can continue pointing to old UUIDs. Invoice tables are introduced by [TAS-475](INVOICES.md), separately from the catalog changeset.

Global catalog mutations use a separate `catalog_mutation_receipts` table, keyed by actor/action/idempotency key. A transaction advisory lock serializes same-intent races; request hashes distinguish conflicts, and the exact protobuf response is durably replayed. Retry authority is checked **before** receipt lookup. Receipts retain the actor UUID and response, never credentials; cleanup/retention integration is future work (retention exceeds the required 24-hour retry window).

The plan mutation, receipt and metadata-only global outbox event commit atomically. No global event uses a fake or nullable tenant ID. Separate tenant `idempotency_records`, `outbox_events` and `processed_events` are prepared with FORCE RLS for subsequent billing tasks; neither null context nor another tenant can read/write them. Tenant-scoped subscription/coverage state is implemented in TAS-314, separately from the global catalog. Dispatcher, durable event application and retention workers remain their own tasks.

## Local runtime

Use the standalone opt-in [Compose](../infra/billing/compose.yml), not the shared stage. Supply private BILLING_OPERATOR_PASSWORD, BILLING_APP_PASSWORD, BILLING_DEPLOY_PASSWORD through your local secret environment. The bootstrap uses service-specific non-inheriting roles and a separate billing_liquibase schema. Runtime rejects a superuser, BYPASSRLS, table owner or role membership.

`BILLING_CATALOG_CURSOR_KEY` is random Base64 of at least 32 bytes, shared between replicas. Without it, listing returns POLICY_NOT_CONFIGURED. The standalone Compose requires the key rather than inventing a default. Trust providers are explicitly injected; no permissive bootstrap is included. HTTP/Actuator is 8080 and gRPC 9090 inside Compose; local mappings are 8090 and 9100. The database uses no host port.

```sh
docker compose -f infra/billing/compose.yml config --quiet
python3 scripts/harness.py check --module billing-service
```

`BillingCatalogIntegrationTest` uses its own PostgreSQL 16, real Liquibase/app LOGIN/RLS and real mTLS/JWS RPCs. It covers repeat bootstrap, draft/public selection, current-offer resolution, numeric validation, concurrency, idempotency conflicts, rollback on outbox failure, immutable SQL, tenant A/B isolation and administrative scope denials. It does not prove external gateway/PSP or shared-stage E2E.

## Commercial configuration — TAS-519

[Commercial readiness](COMMERCIAL-READINESS.md) supplies the private versioned seller/market/Stripe/FX policy, exact EUR→USD invoice terms and a guard before provider invocation. Default live readiness is false; operator configuration, credential attestation and real FX/provider adapters remain required. Tenant-service owns policy history; billing-service owns immutable FX quotes.

[Verified payment settlement TAS-352](SETTLEMENT.md): atomic invoice/ledger/coverage and protected Root activation contracts.
