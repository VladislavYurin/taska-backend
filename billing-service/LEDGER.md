# Financial ledger foundation — TAS-476

The ledger adds immutable normalized money movements, invoice matches and final allocations
beside [invoices](INVOICES.md). It is an internal billing Java/SQL boundary; this task adds no
browser/REST/gRPC payment command. PSP/bank verification, settlement, refund orchestration
and subscription effects belong to TAS-352/357/358 and the ingress owners.

## Facts and source identity

`FinancialLedger.record(tenant,evidenceId,key)` requires verified SERVICE identity from
billing-service, action `recordFinancialFact`, write and `ledger:write`. Its mandatory
`EvidenceAuthority` resolves the actual verified movement on the supplied transaction.
The default fails closed. A caller cannot provide `paid=true`, an amount or bank details as
proof through this entry point. Provider/signature/merchant/environment verification is the
responsibility of the trusted ingress/bank/refund adapter, not browser metadata.

Each fact snapshots provider, merchant/account configuration ID, SANDBOX/LIVE environment,
external operation ID, PAYMENT/REFUND/CHARGEBACK/CHARGEBACK_REVERSAL, exact amount/currency,
financial occurrence, observation and confirmation time, verifier, optional opaque payer and
private evidence reference/digest. WIRE is EUR-only and requires verifier and original payer.
EXACT stores occurredAt; DAY retains the verified half-open evidence interval without
inventing midnight or substituting confirmation time. PostgreSQL precision is microseconds.

Global `(provider,provider_account_id,environment,external_fact_id)` uniqueness prevents
cross-tenant double booking while allowing distinct sandbox/live source identities. The
same normalized movement under another delivery key reuses the original fact. A different
movement for the same source conflicts without revealing another tenant's document. Durable
receipts separately bind tenant/action/key to requestHash; changing the evidence ID or target
with a previous key conflicts. Retries always require current verified service context.

## Matches, allocations and compensation

A payment may match at most one invoice. `match` locks invoice then source and records
`min(available principal, unmatched invoice total)`. This does not allocate money or confer
paid rights. For 12 EUR received against 20 EUR, matched=12, allocated=0 and unallocated=12.
For 25 EUR received and a pending match20, unallocated is still25 and matchedUnallocated is20; only final allocation20 leaves unallocated5. Subtracting the pending match here would lose money in the conservation equation. Returned principal is excluded from the pending matched amount; the original match is retained.

`allocate` accepts match IDs, not a client price. It requires the mandatory
`EligibilityAuthority` decision from the settlement owner, locks invoice and sorted source
roots, checks full exact coverage, and appends one allocation per original match. Missing
eligibility fails closed. A deferred PostgreSQL constraint rejects a committed partial final
allocation even through direct runtime SQL. Database guards check tenant, source, invoice,
currency/exponent, identical match amounts and remaining principal. Allocations can never
exceed the original receipt or invoice total. Competing PSP/WIRE receipts remain separate;
a second full receipt cannot create another coverage/allocation of that invoice.

Allocation is a storage primitive for the enclosing settlement transaction. It does **not**
mark an invoice PAID or create a subscription period by itself. TAS-352 must append these
facts and its fact-backed invoice/period/outbox effects using the same TenantTransactionExecutor;
nested calls join its physical transaction. A failed outer transaction removes the complete
allocation batch, receipts and events. No public RPC exposes standalone allocation.

REFUND and CHARGEBACK append separate movements linked to the original payment. Optional
allocationId identifies returned allocated principal; absent means unallocated principal,
including excess. CHARGEBACK_REVERSAL points to the original chargeback and its same principal
bucket. Guards serialize on the root payment, prohibit overspending/restoring more than the
original debit, and preserve original facts, matches and allocations. Correcting evidence
requires a separate compensating fact rather than an UPDATE/DELETE.

`financial_balances` computes, in one tenant-scoped SQL statement:

```text
received = netAllocated + unallocatedRemaining + confirmedNetReturned
```

All values are exact nonnegative int64 minor units with exponent2, separately per EUR/USD.
For 25 EUR on a 20 EUR invoice: receipt25, allocation20, unallocated5. A confirmed return of
those5 changes unallocated to0 and net returned to5; the allocation20 and subscription are
unchanged. Refund submission/approval is not a confirmed money movement. Actual execution,
return cases, full-product-refund policy, final-loss coverage revocation and calendar behavior
are implemented by their respective policy/orchestration tasks.

## Access, migration and verification

[0005-financial-ledger.sql](src/main/resources/db/changelog/0005-financial-ledger.sql) appends
four FORCE RLS tables and a security-invoker balance view. Applied 0001/0002/0003 and commercial 0004 remain
unchanged. Composite foreign keys prohibit foreign-tenant roots, parents, matches, invoices
and allocation references. Runtime has SELECT/INSERT, plus UPDATE(id) on payment facts only
for PostgreSQL row locks; triggers prohibit every actual fact/link UPDATE or DELETE.

`get`/`balance` require current financial owner/admin authority through the same FinancialAccess
as invoice/subscription reads. Tenant/project ADMIN or ordinary GLOBAL_ADMIN credentials do
not grant financial rights. Every command emits metadata-only outbox events with record IDs;
no evidence, bank references, payer details or credentials are published. Receipt/event writes
are atomic with the ledger change. Retention/purge remains the separate financial retention
workflow; this module never deletes history to repair a reconciliation error.

```sh
python3 scripts/harness.py check --module billing-service
```

`FinancialLedgerIntegrationTest` runs PostgreSQL16 with deployer/migrator and nonprivileged
runtime LOGIN: concurrent duplicate facts, source conflict/environment isolation, 25→20+5,
partial/full allocation, PSP/WIRE races, concurrent excess returns, compensation bounds,
RLS/ACL, exact/DAY evidence, int64 limits, direct SQL guards, outbox and enclosing-transaction
rollback. The shared subscription fixture upgrades 0003/0004→0005 with an issued invoice and
checks preservation/checksums; the catalog fixture exercises a fresh full migration.

These are database/storage-boundary integration checks, not external PSP/REST/stage E2E.
Apply 0005 before enabling ledger adapters. Downgrade needs a consistent backup or a reviewed
forward migration; original financial history must survive operational recovery.
