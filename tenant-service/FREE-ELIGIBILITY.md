# FREE eligibility authority — TAS-472

`FreeEligibilityService` in [free-eligibility.proto](../grpc-common-lib/src/main/proto/v1/free-eligibility.proto) owns one FREE opportunity per confirmed identity. Paid companies and memberships in other companies do not consume it. The opportunity binds to one company, so FREE → paid → FREE in that company is allowed. A new owner uses their own opportunity; transfer never resets the old owner's marker. Several owners' markers may bind the same company.

## Admission and transactions

CreateTenant prepares current verified auth identity, dedicated KMS HMAC and current purpose assessment before opening its write transaction. Admission writes the ledger on the same connection as tenant, membership, creation receipt and outbox. Failure rolls everything back. Paid creation does not call these providers. Successful creation replay uses durable creation facts without requiring them again.

Internal `AdmitFreeTenant` requires a signed billing-service delegation, tenant/action binding, write scope, a UUID operation ID and expected owner version. SERVICE additionally needs free:admit. TAS-354 preserves an ordinary owner WEB_TENANT/USER_PAT/USER_OAUTH credential; administrative kinds require the separate current AdmissionAuthority, never a role bit. It loads the current owner, prepares external evidence outside SQL, then locks/rechecks owner/version/ACTIVE lifecycle and atomically consumes eligibility with its admission receipt. A concurrent transfer rejects stale admission. A global transaction advisory lock serializes consumption, rotation checks and disposition. Same-company retries preserve usedAt/reviewDueAt.

The ADMITTED/CANCELLED proof in migration0013 survives generic receipt expiry. `GetFreeAdmissionReceipt` is restricted to billing SERVICE/free:admit recovery; `CancelFreeTenantAdmission` seals an unaccepted operation against late admission and returns ADMITTED when admission already won. Exact owner/version binding is retained until lawful disposition. [Plan commands and recovery](../billing-service/PLAN-CHANGES.md) describe both databases and the offboarding fence.

This is a durable admission intent for TAS-354, not a subscription mutation. That orchestrator must retry with the same operation ID, enforce its subscription/owner fences and finish the admitted same-company change. Admission does not issue entitlements, activate a company or change a plan.

## Identity, keys and purpose

`IdentityAuthority` returns auth's current verified canonical email and exact user ID with canonicalization version AUTH_EMAIL_V1. Caller-supplied email, guessed aliases and device fingerprints are not accepted. The lower-case stripped email is validated and used only in memory. A purpose-separated message is signed through `HmacAuthority` using a dedicated versioned FREE HMAC-SHA256 key. Production has no key material or fallback hash. RPCs, events and errors exclude email, digest and key material.

The active key writes new markers. All retained matching versions must remain available to KMS; admission/read checks every persisted ledger key version is covered. Missing historical keys fail closed with POLICY_NOT_CONFIGURED. Rotation does not reset eligibility or extend review. A verified email change can add a digest alias to the live binding with original usedAt and earliest reviewDueAt. A re-registered current owner can adopt its email-matched live binding; subsequent email changes cannot reset it.

`PurposeAuthority` supplies an approved versioned assessment valid for at most twelve calendar months. First use snapshots usedAt/purposeVersion/reviewDueAt, capped by assessment validity. Reads/retries never extend it. Expired/mismatched purpose or marker review gives POLICY_NOT_CONFIGURED, never AVAILABLE. Explicit REVIEW through an authorized closed case renews purpose/review within twelve calendar months of that decision and leaves usedAt fixed. There is no automatic TTL reset.

## Deletion and lawful disposition

`ApplyFreeEligibilityDisposition` accepts an opaque case ID from verified tenant-service/auth-service SERVICE with write + free:retention. `DispositionAuthority` resolves an immutable, closed, currently authorized primary case and binds decision version to exact targets. Auth-service is limited to account minimization. The port enforces the deletion fence and proves current verified identities; old profiles, archives and backups must not reconstruct identity.

MINIMIZE_ACCOUNT clears user references while retaining a live company binding. MINIMIZE_TENANT clears all user, company and operation references; only digest/keyVersion, usedAt, purposeVersion and reviewDueAt remain. Before clearing, current verified emails for every live user reference must be covered; new verified aliases are inserted in the same transaction. Missing/stale coverage rejects the step. SQL guards terminal tenant deletion/DELETED until explicit cleanup removes bindings. Future offboarding writers must call this hook before destroying primary identity evidence or finalizing deletion.

ERASE removes exact markers under a lawful-erasure/purpose-ended decision. Matching then stops, even if another FREE admission follows. An immutable disposition receipt stores case ID, decision version, action and appliedAt without subject/marker references. Completed case replay neither reconstructs identity nor erases a newly created marker. Receipt failure rolls back disposition. Restore procedures must replay primary erasure decisions before serving matching; archives are never an alternate eligibility source.

## Creation options and activation

`GetTenantCreationOptions` accepts verified WEB_GLOBAL session + read and returns published plans plus AVAILABLE/USED. USED never identifies the former company/account. Tenant credentials and anonymous callers cannot use it. Intended frontend adapter: `GET /api/v1/account/tenant-creation-options`, pending gateway/session integration TAS-315/316. The current public REST OpenAPI does not advertise this unavailable endpoint.

Primary ports have fail-closed defaults: identity/catalog/disposition unavailable → DEPENDENCY_UNAVAILABLE; missing purpose/KMS policy → POLICY_NOT_CONFIGURED. Configure real auth, published catalog, dedicated KMS, purpose/disposition and RpcContextSecurity providers before activation. This backend does not claim real KMS/auth integration, complete offboarding, public REST or stage E2E.

## Verification and rollout

`python3 scripts/harness.py check --module tenant-service` requires fresh `FreeEligibilityIntegrationTest`. Its private PostgreSQL16 covers deployer→migrator upgrade from migration4, app LOGIN, atomic failures/concurrency, rotation/review/erasure, account/company minimization, owner races, one-connection pool and real loopback mTLS/JWS through production policies. Primary providers are test fakes; this is protected-RPC integration, not external public E2E.

Migration0005 is additive; old changesets are unchanged. Provision primary providers and retention cases before enabling FREE admission. Rollback disables new admissions while preserving ledger and receipts. Dropping tables or restoring pre-erasure snapshots would reset history and is not a recovery procedure.
