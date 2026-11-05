# Immutable subscription invoices — TAS-475

The delivered boundary is `BillingService.CreateInvoice/GetInvoice/ListInvoices` in
[billing-catalog.proto](../grpc-common-lib/src/main/proto/v1/billing-catalog.proto).
Field numbers and DTOs match the B2B blueprint. Public gateway REST, calendar/plan-change
policy, payment ingress, ledger/allocation and commercial production activation remain
separate tasks. This layer issues an obligation; it never charges money or grants coverage.

## Issuance and retries

`CreateInvoice` accepts plan UUID, INITIAL/RENEWAL/PLAN_CHANGE, EUR/USD, PSP/WIRE and
buyer details. It accepts no amount, seller, tax rate, target period, expiry or payment-success
claim. The selected published PAID version supplies the immutable gross company price.
Historical published versions remain usable when the authoritative assignment permits them.

The mandatory `InvoiceDependencies.ObligationAuthority` resolves current eligibility and a
stable server-owned operation/target identity inside the locked subscription transaction.
The subscription's tenant UUID is its identity (one subscription per tenant). Calendar,
plan-change and tenant-lifecycle owners must provide the actual target period and original
creation deadline. A replacement has a new operation/target and links the old invoice;
issuing it never extends the original tenant deadline. An existing operation keeps its identity
across retries and expiry. This service does not invent a period from the request time.

A subscription row lock serializes workers. PostgreSQL additionally enforces unique
`(tenant_id,operation_id)` and `(tenant_id,target_key,purpose,plan_version_id,currency)`.
The payment method is deliberately absent from the business key: PSP and WIRE cannot
create two obligations for the same target. A different method returns the already issued
invoice and its original method/instructions. Changing buyer details on that obligation
conflicts; it cannot edit the old document.

Durable receipts bind tenant, actor, idempotency key and SHA-256 of the exact payload.
Identical retries return the exact original protobuf response; a changed payload with the same
key returns IDEMPOTENCY_CONFLICT. Current financial authority is rechecked before every
replay. A different key can still find the existing business obligation without requoting it.

The server assigns a globally unique sequence number. Sequence gaps on rollback are allowed;
a number never identifies a caller-supplied UUID. Invoice, lines, receipt and metadata-only
`InvoiceIssued` outbox event commit together. Events contain invoice/plan IDs, without buyer,
seller, document or bank details. Issuance leaves subscription version and coverage unchanged.

## Commercial snapshots

`CommercialPolicy` is a mandatory provisioned seller/tax/FX/method-readiness adapter. It
receives the catalog plan, never a caller price. The default returns POLICY_NOT_CONFIGURED;
missing assignment/financial trust adapters also fail closed. Test commercial data is test-only.
No fallback invents a seller, tax rate, exchange source or enabled payment method.

Every amount is an exact nonnegative signed-int64 minor-unit integer with exponent 2.
All lines/taxes share the invoice currency, `net + taxes = total`, and line totals sum to the
catalog gross. Tax is included within the gross. USD requires an approved fresh quote with
source, positive decimal rate, observedAt and expiresAt; conversion rounds HALF_UP exactly
once. Approval/source/maxAge and country/tax/method readiness belong to CommercialPolicy;
the store also checks quote time bounds and the exact converted total. Missing/expired FX
fails rather than switching currency. WIRE is EUR-only and requires bank instructions.

Validity is provisioned separately from renewal lead time (1..30 days; a policy may choose
the blueprint default 7 days). `payable_until` is capped by the original creation deadline
when supplied. Catalog terms, commercial profile version, seller, buyer, lines/taxes, FX,
currency, amount, target period and deadline are copied once. Subsequent changes cannot
reprice or rewrite an issued document.

## Database and financial facts

[0003-invoices.sql](src/main/resources/db/changelog/0003-invoices.sql) appends a changeset;
0001/0002 are unchanged. All three new tenant tables use FORCE RLS and the existing
nonprivileged billing runtime role. Composite FKs protect lines, replacement references and
`subscription_periods(tenant_id,invoice_id) -> invoices(tenant_id,id)`.

DRAFT exists only inside the issuing transaction. Deferred integrity checks reject a commit
without 1..100 complete lines and exact header totals. Sealing DRAFT -> OPEN is the only
permitted update in this layer. Issued invoices/lines cannot be edited/deleted/appended to.
A ledger migration must explicitly add supported fact-backed state transitions; direct SQL or
a browser confirmation cannot set PAID here. Allocated/matched amounts start at zero;
remaining equals total. Elapsed time alone does not overwrite the issued OPEN snapshot.
Expiry, partial/full settlement and refunds belong to the financial-fact/projector tasks;
there is no client-controlled settlement RPC. A future projector must overlay mutable
financial state on the immutable commercial snapshot, rather than rewrite its terms.

## Access and pagination

Each RPC requires a verified tenant-bound mTLS/JWS context and current primary financial
authority. Ordinary access is for the ACTIVE current owner only. Tenant/project ADMIN or
a GLOBAL_ADMIN using ordinary USER credentials does not establish financial authority.
Administrative access requires current primary validation plus PLATFORM or applicable
TENANT_SET scope; issuance requires MODIFY and write, reads require READ/MODIFY and read.
Missing authority providers fail closed. These checks are shared with subscription reads.

Lists use descending `issued_at,id` keyset order, default 50/max100, with a 15-minute HMAC
cursor under `BILLING_CATALOG_CURSOR_KEY`. Binding includes purpose, tenant, actor,
session/credential/grant generations, scope and page size; authority is rechecked on each
page. Listing is a current view; it is not a cross-request frozen snapshot. There are no raw
credentials or document links in a cursor. Missing cursor configuration disables listing.

## Verification and rollout

```sh
python3 scripts/harness.py check --module billing-service
```

The required `InvoiceIntegrationTest` uses PostgreSQL16, deployer/migrator/runtime LOGIN,
RLS, concurrent workers, replay/conflicts, rollback, sealing, EUR/WIRE and USD/FX validation,
period/deadline snapshots, financial denials, pagination and real mTLS/JWS calls through the
production policy map. Subscription tests upgrade 0002 -> 0003 with existing subscription
data and unchanged checksums, then exercise the new invoice FK with explicit test invoices.
Catalog tests cover a fresh full migration. Testcontainers cleans up its own containers.

After final review rerun the billing check on the final head. This establishes the delivered
internal boundary, not public REST/PSP/shared-stage E2E. Production remains closed until
trusted assignment, commercial, financial-owner and transport authorities are provisioned.
Apply 0003 before code using the invoice FK; downgrade requires restoring a consistent backup
or a separately reviewed forward migration. Never delete financial history to roll back.
