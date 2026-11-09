# Payment recovery — TAS-353

`PaymentReconciliation` reads the original provider/account/environment and frozen profile from
an existing durable attempt. It never invokes `createCheckout`, refund or a current-provider
fallback. A successful lookup needs the original object ID, matching invoice/money and the
provider's actual occurrence timestamp. Missing IDs, missing timestamps, contradictory facts or
missing original adapters go to manual review. An expired PSP idempotency TTL never permits
another submission. ACCEPTED/SUBMITTED without an object ID also needs manual proof; lookup by
an invented object/key is not supported by the PaymentProvider port.

A new migration installs the GLOBAL_FINANCIAL work directory and atomically enqueues attempts
on insert, including backfill of existing attempts. It stores immutable tenant/attempt/operation
correlation pointers, not credentials or provider payloads. Tenant financial data remains behind
its existing RLS. There is no company-lifecycle filter: deleted/cancelled companies still have
financial recovery, while TAS-352 decides whether money can create coverage/activation.

The pump claims one item immediately before its private self-mTLS/JWS RPC. Claims have a
120-second lease and random token; only the live token can record completion/retry. A process
crash makes the item eligible after lease expiry. RPC authenticates current billing-service
identity/generation, tenant, action `reconcilePaymentAttempt`, `write` and `payment:reconcile`.
The handler loads correlation and origin from the durable job, never from request metadata.
Financial effects remain idempotent even if a lease expires during an external read. No SQL
transaction stays open during provider/Root calls.

Batch size defaults to 10 (1..100). Automatic retries default to 8 (1..100), with persisted
30s, 60s, 120s… backoff capped at 1h. PENDING, UNKNOWN, provider/RPC outages and lost Root ACKs
retry. Exhaustion retains a MANUAL row, stable reason, total attempts, original creation age and
correlation. Manual rows are not silently dropped. Internal replay requires the tenant-bound
billing service action `replayPaymentReconciliation` plus `write`/`payment:reconcile:replay`, and
retains tenant/correlation/age and total attempts. After exhaustion an operator replay allows
one further check; another failure returns to MANUAL. No public/manual-payment API or financial
admin UI is added; TAS-518 owns that separate interface and its GLOBAL_ADMIN checks.

Successful lookups write the same immutable proof ID/canonical digest used by signed callbacks.
TAS-352 settlement is called through the existing private RPC. Lost ACKs reuse the stored proof;
they do not require the provider to be reachable again. Concurrent workers and later callbacks
reuse one ledger/coverage decision. Provider success does not itself mark an invoice PAID.

Activation is explicit: `TASKA_BILLING_RECONCILIATION_ENABLED=false` by default. Enable only with
retained original PaymentProviderRegistry and ProviderWebhookRegistry profiles, enabled inbox,
real RpcContextSecurity/current authority, the existing self-mTLS inbox/settlement stubs and a
self-mTLS `ReactorPaymentReconciliationServiceStub` provisioned against this billing service.
Missing dependencies fail startup; no production credentials or fake adapters are supplied.
`TASKA_BILLING_RECONCILIATION_MAX_ATTEMPTS` and `TASKA_BILLING_RECONCILIATION_BATCH_SIZE` set the
bounds above; `taska.billing.reconciliation.poll-interval` defaults to 5s.

The private Actuator Prometheus endpoint exports `taska_billing_reconciliation_manual`,
`taska_billing_reconciliation_pending`, and `taska_billing_reconciliation_oldest_seconds`.
Prometheus scrapes billing on its internal 8090 port; the matching alerts report any MANUAL work
for 5 minutes and unresolved work older than 24 hours. These are operational thresholds, not
payment confirmation or a new contractual SLA. Logs contain only IDs and fixed reason codes.
`PaymentReconciliationStore.manual(limit)` is the bounded internal operator handoff (reason,
origin, correlation, total attempts, createdAt); it is not a tenant-user endpoint.

PostgreSQL/RLS and actual mTLS/JWS tests cover lost callbacks, duplicate workers, lease expiry,
restart/Root-ACK loss, stable replay, provider outage/backoff, TTL expiry without resubmission,
identity/occurrence failures, late callbacks and deleted-company settlement. These local fixtures
do not establish real Stripe credentials, a deployed enabled worker or public checkout readiness.
