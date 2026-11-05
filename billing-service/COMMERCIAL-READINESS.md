# Commercial configuration and readiness — TAS-519

This implements the operator configuration and server-side pricing/readiness components of [C02/D250](../docs/b2b/commercial-decisions.md). Actual seller/merchant credentials and an external FX adapter remain operator inputs. No real Stripe account, exchange-rate default or seller identity is invented or enabled by deployment.

## Configuration authority

The private [commercial-configuration.proto](../grpc-common-lib/src/main/proto/v1/commercial-configuration.proto) defines a closed typed schema: seller/version/legal identity/address, approved country/buyer type/currency/method combinations, inclusive tax rule, document/refund policy versions, Stripe account/environment/secret references, verified seller bank calendar/version/digest and approved FX source/version/maxAge. Stripe is the selected PSP; WIRE is EUR only. ISO country codes, exact decimal tax rates, IBAN checksum, credential environment, reference formats and live-calendar provenance are validated. A live profile cannot use a fixture calendar. Sandbox PSP configuration may omit the seller; it never implies live readiness.

`tenant-service` owns the immutable `MERCHANT_CONFIGURATION` JSON versions in its existing platform_configuration_versions. New migration0004 extends the allowlist for exactly this private JSON policy; it does not permit arbitrary JSON flags or tenant overrides. A disabled, incomplete LIVE profile is seeded without seller/credentials. Enabled publication requires all referenced profile components and an explicit confirmation. Existing scalar flags remain24 and never serialize the private policy.

- `GetMerchantConfiguration`: current administrative view with version/effectiveAt/environment/enabled/static completeness and missing requirement codes. No seller, account or secret references are returned.
- `PublishMerchantConfiguration`: canonical UUID intent, expected version and confirmed=true; only verified PLATFORM+MODIFY, global web MFA/session or applicable administrative integration grant. Ordinary tenant credentials cannot publish. Version/audit/receipt and metadata-only global event commit together. Replays return the original view, stale versions conflict and failures roll back.
- `ResolveMerchantConfiguration`: private billing-service SERVICE caller with read+commercial:resolve; optional version resolves an immutable historical profile. mTLS/JWS, current service authority and exact action are mandatory. A user/administrator cannot use this service identity by supplying a request field.

The policy JSON and refs are never included in event payloads or error details. A version row is an immutable audit entry; raw API/webhook keys are rejected. Secret references have the shape `secret://live/...` or `secret://sandbox/...`, with distinct API and webhook references. The configured credential authority resolves them privately and attests the actual provider client's environment/account/references; syntax alone never establishes live readiness.

The internal source `GrpcMerchantConfigurationSource` signs each fresh service request and uses a deadline-bound supplied mTLS stub. Billing enables it only when both stub and trust providers exist. Missing configuration, credential authority or FX provider fail closed. No plaintext channel or permissive provider is created automatically.

## Invoice and checkout guard

`CommercialReadiness.invoice(transaction, serverPlan, selection)` takes the server-resolved immutable published PAID catalog plan, not a browser price. It requires an approved exact country/buyer/currency/method tuple and a ready current profile. It returns immutable InvoiceTerms containing the configuration version, environment, seller, tax/document/refund versions, gross/base/net/tax minor units, selection and optional stored FX evidence. The invoice owner must persist this result atomically with its invoice using the supplied transaction connection. Public invoice lifecycle/settlement and the PSP adapter remain their separate tasks; this component does not mark anything paid.

EUR preserves catalog gross exactly. USD uses only the configured EUR→USD FxQuoteProvider: positive numeric(20,10) rate, matching source/version/environment, microsecond UTC timestamps, nonfuture observation and both provider validity and configured maxAge. `now >= validUntil` or `now >= quotedAt+maxAge` rejects a new USD quote. Final gross conversion rounds HALF_UP once in minor units; no binary floating point. Tax is split out of that inclusive gross, never added to it. No unsupported market/method silently reprices the company or changes the selected currency. A failed USD quote explicitly offers EUR only when that same EUR market/method is approved; the caller must select it.

`fx_quotes` in billing DB stores immutable rate/source/sourceVersion/time/environment/configurationVersion. App role has SELECT/INSERT only. Old snapshots are never refreshed when the source/profile changes. Arithmetic validation precedes insertion, and an outer rollback removes FX evidence with the failed invoice operation.

`CommercialReadiness.checkout(storedTerms, actualEnvironment, providerCall)` checks current enablement/approved selection, then resolves the invoice's exact historical configuration and verifies its merchant/credential binding before invoking providerCall. The provider receives the frozen amount/currency/configuration version and private credential references. A new seller/rate does not reprice an old document, and an expired old FX observation does not invalidate its frozen amount. Changing merchant account or environment blocks a new provider call rather than charging through a different identity. The terms must be loaded from the server-owned invoice snapshot; this is an internal API, never an HTTP payload accepting caller amounts. Provider state/idempotency/UNKNOWN reconciliation belongs to the payment adapter.

`livePaymentEnabled(selection)` additionally checks actual credential attestation and fresh FX for USD; it returns false on missing/unavailable inputs. Sandbox always returns false for this live capability. Runtime readiness is distinct from the registry's static liveProfileComplete bit.

## Migration, verification and activation

Apply tenant0004 and billing commercial-fx0004 with the service's deployer/migrator roles; earlier applied SQL is unchanged. The tenant upgrade introducing a private JSON row must be coordinated with the new tenant-service binary: an old scalar-only binary does not understand that row. Older profile snapshots and FX rows are retained. Rollback does not erase financial evidence or rewrite old invoices.

Required checks:

```sh
python3 scripts/harness.py check --module tenant-service
python3 scripts/harness.py check --module billing-service
```

MerchantConfigurationIntegrationTest uses PostgreSQL16/app LOGIN, preceding-schema upgrade/checksums, safe scalar/view/event isolation, confirmation/authority, immutable replay, rollback and real mTLS/JWS publisher/private reader policies. CommercialReadinessIntegrationTest uses a separate PostgreSQL16 and synthetic operator/provider fixtures: missing/incoherent profile, credential/environment mismatch, unsupported markets, exact EUR/USD/tax rounding, stale/missing/foreign FX, frozen old terms after profile/rate changes and rollback. No live credentials or external PSP calls are used. Public REST, actual Stripe/FX integration and deployed B2B payment E2E are not established by these tests.
