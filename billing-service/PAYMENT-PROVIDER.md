# PaymentProvider — TAS-320

The reactive port is part of billing-service; the application/catalog/invoice/ledger retain their
existing modules, persistence and runtime configuration. The adapter boundary contains no PSP SDK
or client payment assertions. Invoice UUID/total are server-owned; owner/tenant authorization and
merchant-profile/account/environment validation are done before entering a real adapter.

FakePaymentProvider is an explicit in-memory TEST adapter with no production Spring bean. LIVE,
partial refunds and recurring are unsupported. It cannot contact or charge a PSP. Its injected
Clock controls actual terminal payment/refund occurrence, which is pinned across later callback
deliveries; default construction uses UTC. Provider keys are merchant scoped and bind operation,
invoice and amount/currency. UNKNOWN/PENDING are unfinished observations, not PAID. Retry keeps
the same provider identity. Confirmation races cannot publish contradictory terminal outcomes.

A declined refund has returned no money and permits a new operation/key; PENDING/UNKNOWN/SUCCEEDED
reserve the full principal and reject another refund. Old keys keep their original result.
Signed fake webhook bodies bind merchant/environment/event identity. The same event ID may exist
at two merchants, but a signed body from one merchant never validates as the other's event.

The contract suite is inherited by fake and future sandbox adapters. Fake contract/concurrency
checks are required by billing-service.verify. Durable attempts/inbox, financial settlement,
PSP verification/production Stripe, financial exceptions and external E2E remain TAS-349/351/348/357/378.
See [settlement](SETTLEMENT.md) and [commercial readiness](COMMERCIAL-READINESS.md).

## Stripe sandbox adapter — TAS-348

`StripePaymentProvider` is an opt-in reactive `WebClient` adapter. It is disabled by default and
accepts only `sk_test_` credentials and `PaymentProvider.Environment.TEST`; no live merchant can be
registered through this configuration. Enable it only in an isolated sandbox with:

```text
TASKA_BILLING_STRIPE_ENABLED=true
TASKA_BILLING_STRIPE_API_KEY=<sandbox secret reference resolved by deployment>
TASKA_BILLING_STRIPE_WEBHOOK_SECRET=<sandbox secret reference resolved by deployment>
TASKA_BILLING_STRIPE_MERCHANT_ACCOUNT=<sandbox merchant account>
TASKA_BILLING_STRIPE_PROFILE_VERSION=<immutable merchant profile version>
TASKA_BILLING_STRIPE_SUCCESS_URL=https://<allowlisted-host>/billing/return
TASKA_BILLING_STRIPE_CANCEL_URL=https://<allowlisted-host>/billing/cancel
TASKA_BILLING_STRIPE_ALLOWED_REDIRECT_HOSTS=<allowlisted-host>
```

The adapter sends Checkout Session creation and refund requests with the durable provider
idempotency key, reads payment state from the Checkout Session, and verifies Stripe webhook
signatures against the raw body and bounded timestamp tolerance. A return URL never changes the
local payment state; the existing attempt/reconciliation/inbox flow remains authoritative.

The configuration deliberately does not resolve live merchant credentials or enable live PSP
actions. That requires the versioned operator merchant profile and the separate live-enable task.

Refund observations carry `providerRefundId` for the exact attempt. A new attempt after decline cannot reuse an earlier attempt’s event ID; the rejection preserves the new attempt’s pending state and the old verified event. Payment observations have no refund ID.

Durable orchestration and activation: [payment attempts](PAYMENT-ATTEMPTS.md).
