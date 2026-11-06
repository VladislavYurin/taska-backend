# Verified payment settlement — TAS-352

`PaymentSettlementService.ApplyVerifiedPayment` accepts only tenant/evidence IDs. Its caller is
verified `SERVICE billing-service`, action `applyVerifiedPayment`, scopes `write` and `ledger:write`.
The supplied mTLS channels, signing/replay and online service authority remain mandatory.
`EvidenceAuthority` resolves a durable normalized success from the trusted ingress/bank owner on
the billing connection. It verifies the original merchant/account/environment and provider operation
against the frozen merchant profile; a browser return, invoice state or callback delivery timestamp
is never financial evidence. Redeliveries must resolve the same normalized movement. The default
producer denies; PSP ingestion is TAS-351/348 and bank aggregation/exceptions are TAS-357.

Before opening SQL, billing obtains uncached Root `TenantPaymentState`. In SQL it locks the
subscription, invoice, then payment, validates tenant/invoice/operation/profile version/method/currency
and frozen selection generation, and records the immutable financial fact. Exact full timely payment
creates one match, allocation, PAID invoice, coverage, entitlement update, immutable settlement audit
receipt and sanitized outbox, in one transaction. Failure rolls everything back. The source financial
identity deduplicates different event IDs and parallel webhook/reconcile. Source facts cannot be
rebound to another invoice. Replays return the original decision and never grant a second month.

Received money that cannot settle remains unallocated with `REVIEW_REQUIRED` and a stable reason:
wrong/full amount, imprecise day-only evidence, expired invoice, ineligible company, superseded
selection or already paid invoice. This is not PAID or an invented refund. Financial cases, multiple
bank receipts, excess and returns remain their owner tasks. A pending selection is retryable rather
than a final rejection. No PSP is called by this processor.

PSP periods start at actual success; WIRE first/resumed periods start at verified confirmation.
Timely same-plan renewal preserves the calendar anchor. An expired/revoked period provides no
remaining time: a new payment starts a full new month. Plan changes use `PlanCoveragePolicy` and
the locked current intent/generation from TAS-354. An old disputed payment cannot restore revoked
coverage. Original invoice snapshots/lines/terms remain sealed; get/list project the current PAID
state and paid/remaining amounts over that document.

For INITIAL, the committed decision/outbox contains the creation operation and timely exact payment.
After billing commit, `GrpcInitialPaymentDelivery` invokes the existing Root lifecycle RPC. Root
resolves `GetInitialPaymentDecision` through `GrpcInitialPaymentDecisions` and checks its own current
creation/deadline/cancellation/readiness guards in a separate transaction. AUTO_CLOSED can activate;
OWNER_CANCELLED/DELETING/DELETED cannot. Independent restrictions are not cleared. No foreign DB
transaction is used. If delivery/response fails, billing remains committed and retrying the same
source evidence reuses the same decision and redelivers it; the durable
`TenantInitialPaymentDecisionReady` event is also the outbox retry input. This result distinguishes
financial settlement from Root activation: `requires_tenant_activation` identifies INITIAL, and a
Root denial never overrides its lifecycle. Cancellation racing after the observed Root version is
ultimately decided by Root; any financial follow-up belongs to TAS-357/518.

Migration0009 keeps applied migrations intact, preserves invoice documents and backfills old
PLAN_CHANGE selection generations from their immutable intent under the migration's table lock.
The database refuses PAID without full allocation and a settlement/coverage committed together.
Settlement decisions are append-only and FORCE RLS tenant scoped. Older binaries that only project
the sealed invoice document must be upgraded together with billing before exposing settlement.

`PaymentSettlementIntegrationTest` uses PostgreSQL/app LOGIN and explicit fake verified producer
and Root adapters. `TenantProvisioningRpcIntegrationTest` additionally exercises the actual
billing → Root state → billing decision → Root activation chain using production policy maps,
mTLS/JWS and separate databases. These are isolated service-path checks; they do not establish
external PSP/bank, public REST or shared-stage readiness. After final review rerun both paths:

```bash
python3 scripts/harness.py check --module billing-service
python3 scripts/harness.py check --module tenant-service
```
