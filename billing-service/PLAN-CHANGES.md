# Owner plan commands — TAS-354

The protected [BillingService RPCs](../grpc-common-lib/src/main/proto/v1/billing-catalog.proto)
`ChangeSubscriptionPlan`, `CancelSubscriptionRenewal` and `ResumeSubscriptionRenewal` use current
financial authority, explicit confirmation for change/cancel, `MutationOptions.expected_version` and
an actor-scoped idempotency key. A replay rechecks current authority. A USER credential remains a USER
credential when billing delegates to Root; it never gains administrative or SERVICE authority.

## Intent and coverage

Migration [0008](src/main/resources/db/changelog/0008-plan-changes.sql) adds immutable owner choices,
a selection generation and coverage-to-choice binding. Mutations serialize on the subscription row.
A new choice fences pending invoices from older selections. A paid choice returns `operation_id` and
AFTER_PAYMENT/NEXT_PERIOD; it does not claim a payment or fabricate buyer details. The owner then
issues an invoice with purpose PLAN_CHANGE, target plan, currency, method and buyer. Its obligation
uses that same operation across retries/payment channels. Receipt replay returns the accepted intent;
GetSubscription supplies the current derived view.

- Paid → FREE obtains the current owner's sole FREE admission from Root. It immediately replaces the
  selected terms and supersedes all old/future GRANTED paid periods, preserving their immutable history.
  Old coverage callbacks and cached coverage receipts cannot restore paid access. No money/refund is
  created; stored usage/files/lifetime counters are untouched. Root quota authorities read the new
  limits and retain independent OVER_QUOTA when actual usage plus reservations exceeds them.
- Cheaper paid waits for a separate **full confirmed** invoice outcome. PSP uses actual success time;
  WIRE uses administrator confirmation. It starts a new full UTC calendar month and anchor without
  carrying over the old remainder. Pending/failed payment retains the old rights only until its expiry.
- More expensive paid starts at the captured next paid boundary, preserving the original anchor and
  short-month clamp. A confirmed future period grants no early rights. A later payment after an unpaid
  gap starts a fresh UTC month from actual success/confirmation.
- FREE → paid always needs a fresh confirmed outcome. Expiry/cancel never automatically selects FREE
  or consumes FREE eligibility. Cancel/resume changes renewal intent only: no new period, invoice,
  provider call or revival of revoked autopay consent. Pause/block/dispute remain independent reasons.
- Selecting a different version of the same plan code is rejected with
  PLAN_TERMS_CHANGE_REQUIRES_NOTICE; the catalogue notice/effectiveAt process belongs to TAS-483.

`PlanCoveragePolicy.evidence` normalizes an already verified full `PlanPayment`. Settlement must use
it and `SubscriptionStore.grant` on the same billing transaction/connection, including the current
`SelectionBinding`. Calendar, target, invoice operation and generation are revalidated under the row
lock. An OPEN invoice is an issued document, **not proof of payment**: full financial truth comes only
from `SettlementEvidenceAuthority`. The pre-settlement invoice schema deliberately cannot mark it
PAID yet. TAS-352 owns that atomic ledger/allocation/state integration; the policy tests use explicit
fake confirmed facts as required by TAS-354. No request field/webhook redirect is such a fact.

## FREE crash recovery

Billing commits PREPARED before the Root call. Root stores ADMITTED/CANCELLED decisions in
[0013](../tenant-service/src/main/resources/db/changelog/0013-free-plan-admissions.sql), independently
of generic 24-hour receipt cleanup. A lost reply leaves old rights and a durable blocked intent. A
fresh billing SERVICE context with `write`, `plans:recover`, `free:admit` and
`recoverFreePlanChange` may invoke `recoverFree(operation)` or tenant-bounded
`recoverPendingFree(limit)` (1–100). A host must supply that online-authorized context; an owner PAT
is never converted into a worker. Missing primary policy/trust providers fail closed.

Recovery reads the exact tenant/operation/owner/version proof before trying admission. A definitive
rejection seals the Root operation before billing restores an older pending selection; late admission
cannot spend FREE after that cancellation. ADMITTED wins if it committed first. Billing completion,
period supersession, version, response, entitlement and outbox commit together; database/outbox
failure retains PREPARED for retry without another FREE opportunity. Conflicting commands return
FREE_TRANSITION_PENDING until that decision is resolved.

Lawful Root account minimization clears the proof's owner reference while retaining the operation
tombstone; tenant minimization removes its proofs through the existing authorized disposition hook.
Offboarding must reconcile pending cross-service intents before minimizing their identity evidence
(TAS-495/520). A routing index contains only owner/tenant IDs, allowing the authorized global disposition to visit
ordinary tenant RLS scopes and clear those routing references in its transaction. It grants no access
to admission proof or financial data. No normal DML may rewrite/delete decisions. Generic receipt cleanup alone cannot reset
an admission or fabricate proof for another owner/version.

## Integration and verification

Configure a current `PlanChangeDependencies.Policy` (owner/admin grant plus lifecycle/action rules),
financial read authority, Root identity/KMS/purpose/admin authority and signed mTLS client before
activation. Defaults deny/unavailable. TAS-355 must apply cancellation, lifecycle, current consent and
selection fences before accepting each new autopay attempt; reconcile already accepted attempts once.
TAS-473 consumes entitlement versions. TAS-495 composes Root quota causes with pause/block/dispute
and enforces the same capabilities for UI and domain writes. Avoid billing → aggregate quota →
billing recursion. Public REST/session transport remains TAS-316; no unauthenticated header fallback
or unimplemented public endpoint is advertised here.

`SubscriptionIntegrationTest` exercises actual PostgreSQL migrations/RLS, cheap/expensive/WIRE UTC
calendars, expiry, stale payment fences, FREE roundtrip, current ACL, concurrent expected versions and
lost-response recovery. `FreeEligibilityIntegrationTest` checks durable decisions/cancellation,
owner/admin denials and the production billing client over real mTLS/JWS. The existing three-database
`TenantProvisioningRpcIntegrationTest` additionally composes actual billing downgrade/entitlement
with actual Root storage ledger: bytes and documents survive while the lowered limit produces the
independent OVER_QUOTA cause. The common CapabilityEvaluator consumes those actual terms/causes
and denies issue mutation with READ_ONLY. Primary ACL, identity/KMS and financial providers are
fixture ports.
These are isolated backend integrations, not real PSP/WIRE or deployed public E2E.

```sh
python3 scripts/harness.py check --module tenant-service
```
