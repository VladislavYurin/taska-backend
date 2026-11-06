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
