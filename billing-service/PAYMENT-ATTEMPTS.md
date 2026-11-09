# Durable payment attempts — TAS-349

The coordinator requires explicit `PaymentProviderRegistry`, durable stores and inbox mapping.
Each registry identity binds its adapter to the approved frozen merchant-profile versions.
The sealed invoice profile is checked before operation/attempt writes or any PSP call.
No fake adapter, current-PSP fallback or production credentials are supplied by default. `TASKA_BILLING_ATTEMPTS_ENABLED=false` is the default. Enabling it requires the provisioned
registry and enabled self-mTLS inbox worker; missing dependencies fail startup. The condition is
property based and does not depend on the order in which registry/worker bean definitions load. This core does not publish a public checkout endpoint or real Stripe adapter.

Before createCheckout, a business operation and a sealed-invoice-bound attempt commit. The
operation UUID sent to the provider comes from durable storage. PostgreSQL jsonb equality and
a canonical framed request hash make equivalent JSON replay without treating different payloads
as the same request. Amount/currency come from the sealed PSP invoice. One active attempt per
invoice prevents different caller keys from opening concurrent charges.

Only a committed ACCEPTED→SUBMITTED version claim can call createCheckout. After a timeout,
process crash or cancellation, SUBMITTED/UNKNOWN without an object ID never automatically calls
createCheckout again, even using the same key: the provider may have expired it. Retain the
original operation/key/provider/account/environment and resolve the original object through
verified provider/operator reconciliation (TAS-353); absence of an ID never authorizes a charge.
A failure after claim but before submission may therefore need reconciliation proving no effect.

Known objects are fetched exclusively through their original provisioned provider/account/env.
Responses must match stored invoice, amount, currency and object ID. Version checks and SQL
immutability prevent a stale timeout from replacing SUCCEEDED or erasing/changing the PSP ID.
After the identity commits, the self-mTLS worker registers its route with TAS-351. Lost mapping
ACK retries that same identity; it does not resubmit a charge. The native inbox independently
checks the sealed invoice, operation and frozen profile before establishing its immutable route.
Payment attempt success is a provider observation; the existing settlement/inbox still owns
ledger, coverage and Root decisions. Pending/unknown business operations remain unfinished.

`PaymentAttemptIntegrationTest` is mandatory in billing verify and uses real PostgreSQL, tenant
app LOGIN/RLS and fake/mock provider effects: JSON first create/equivalent/conflicting replay,
merchant/env/invoice denial, concurrent same/different keys, stale terminal updates, cache expiry
and restart, committed-claim crash, original PSP selection, lost mapping ACK, immutable SQL
identity and A/B reads. ProviderInboxRpcIntegrationTest checks actual mTLS/JWS mapping/delivery.
These checks do not establish real Stripe or public gateway/stage E2E readiness.
