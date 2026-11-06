# Authenticated provider inbox — TAS-351

`ProviderInboxService` is a private adapter/worker API, separate from public checkout.
`ObserveProviderWebhook` accepts exact raw bytes only from verified `SERVICE payment-adapter`,
without tenant/actor, action `observeProviderWebhook`, `write` + `inbox:ingest`.
`ProviderWebhookRegistry` must be explicitly provisioned per provider/account/environment and frozen
merchant profile versions. Its adapters authenticate the exact raw body and provider delivery age;
unsupported types still require authentication. The PaymentProvider bridge delegates verification
and normalized outcomes to the adapter; a bounded current/retiring verifier list supports rotation.
No fake/merchant/trust configuration is installed as a production default.

Admission bounds raw body/signature and persists a shared per-merchant minute rate bucket.
Valid input commits an encrypted private payload, digest and normalized observation before ACK.
Deduplication includes provider/account/environment/event ID; conflicting raw bytes cannot rewrite
an existing receipt. Unsupported authenticated events are IGNORED, non-success outcomes never
become financial facts, refund events are retained in QUARANTINED for their refund owner.
The PSP business flag is deliberately not an ingress switch: previously accepted payments keep
receiving callbacks while new charges are disabled.

## Local mapping and asynchronous processing

A raw request never selects a tenant. `RegisterProviderPayment` is restricted to current
`SERVICE billing-service`, action `registerProviderPayment`, `write` + `inbox:map` and its tenant.
It reads the actual sealed PSP invoice, operation and frozen profile under tenant scope and records
an immutable provider/account/environment/payment → tenant/invoice binding. Other tenants and
unregistered frozen profiles cannot establish a route. The checkout/attempt owner (TAS-349) must
publish this binding using the original persisted attempt's provider identity, never browser data.
If the callback arrives before the create response/mapping, its durable RECEIVED row waits for the
binding; ACK does not depend on the arrival order. No charge or provider fetch is executed here.

The bounded pump discovers only mapped, due receipts and calls `ProcessProviderWebhook` over the configured
self mTLS channel. The service-origin SDK obtains fresh authority and a message-bound signature;
there is no manually constructed VerifiedTenantContext or role/header fallback. The handler requires
`SERVICE billing-service`, `processProviderWebhook`, `write` + `inbox:process`, rechecks the stored
route against the signed invoice/currency and creates an immutable canonical success proof.
Different event IDs for one economic payment share the same proof, original occurrence/observation
and digest. Contradictory evidence or foreign mapping is quarantined without settlement.

Only this durable proof implements `PaymentSettlementDependencies.EvidenceAuthority`. Proofs and
routes are GLOBAL_FINANCIAL transport evidence, protected from rewriting by SQL privileges/triggers;
lookup includes the exact requested tenant. The following settlement transaction still owns invoice,
ledger, coverage and Root eligibility. The process handler invokes the protected settlement RPC after
its inbox transaction commits, and marks APPLIED only after successful delivery. Root failure after
the financial commit leaves VERIFIED for retry: the same proof/decision is delivered again, never a
second charge/month. Failed deliveries receive persisted exponential backoff (5 seconds to 15 minutes), so a failing batch does not monopolize subsequent polls. Receipt/evidence tenant identity is also enforced by a composite FK. APPLIED means processed (including REVIEW_REQUIRED), not necessarily PAID.

## Activation and storage

Migration0011 adds the private global financial inbox/routes/proofs and bounded rate buckets; no
applied migration is changed. Explicit least-privilege grants keep immutable sources append-only.
Ciphertext is AES-256-GCM, bound to receipt ID and key ID. Keys are external, retain previous keys
while their private payloads remain readable. Cleanup erases only terminal APPLIED/IGNORED payloads
after 30 days, preserving dedupe/proof/financial rows; unresolved/quarantined payloads are retained.
Raw body, signatures and private keys are never returned, put in outbox or logged.

Default `TASKA_BILLING_INBOX_ENABLED=false` keeps the new RPC/worker disabled. Enabling requires:

- `TASKA_BILLING_INBOX_PAYLOAD_KEY_ID` and `TASKA_BILLING_INBOX_PAYLOAD_KEYS` (private JSON map of
  key IDs to Base64 32-byte AES keys), provisioned outside Git;
- an explicit ProviderWebhookRegistry bean with real merchant/profile-bound verification adapters;
- RpcContextSecurity keys, shared replay/online service authority, current
  `TASKA_BILLING_INBOX_SERVICE_GENERATION`, and self ProviderInbox/PaymentSettlement mTLS stubs;
- the TAS-349 producer binding original provider object identities to sealed invoices.

The private native API is implemented here. Stripe parsing/public webhook transport belongs to
TAS-348; public checkout/gateway activation and real merchant readiness are separate. A fake test
is not evidence of real PSP or stage cutover. Do not disable an already activated inbox merely
because new PSP payments are turned off.

## Verification

`python3 scripts/harness.py check --module billing-service` requires fresh
ProviderInboxIntegrationTest, ProviderInboxRpcIntegrationTest and InboxCipherTest reports in
addition to the existing billing suites. Tests use real PostgreSQL, deployer/migrator/app roles,
production mTLS/JWS policy and the actual self-worker/settlement path, fake signed provider input,
commit failure, duplicates, parallel processing, foreign mappings, expired verification, key rotation,
raw encryption/retention and lost Root response. Root state/delivery fixtures are explicit; the
full billing/Root service path remains covered by TenantProvisioningRpcIntegrationTest.
After final review repeat both applicable billing and tenant suites; public Stripe/stage E2E needs
the separately configured adapters and environment.
