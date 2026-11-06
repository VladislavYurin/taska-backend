# Common capability decisions (TAS-360)

`grpc-common-lib` supplies `CapabilityEvaluator.evaluate(Action, Target)` and
`decisions(Set<Action>, Target)`. Both return reactive decisions from current facts.
There are 93 closed action names from the TAS-304 access matrix. The typed action
table (`capability/actions.json`) records role, scope, lifecycle and channel rules;
unknown/incomplete rules fail initialization. `Capability` and `Capabilities` preserve
the blueprint protobuf fields. The aggregate DTO is for future domain endpoints.

The evaluator requires a genuinely verified Reactor tenant context and an explicitly
supplied `Source`. There is no permissive source, automatic bean or legacy API
activation. Every subscription calls Source again; actor allows are never cached.
Returned facts must bind actor, target and request ID, expire within 30 seconds, and
contain explicit integrity and business results for every requested action. Primary
identity, credential/grant authority and scope must be checked before resolving or
counting target objects. A mismatching selected tenant is rejected before Source is
called. Global actions use an explicitly global target; no default tenant is invented.

Source must compose current primary identity/session/credential/grant, membership,
project ACL, lifecycle, global flags, exact catalog kind and financial entitlement.
These records are internal trusted inputs, never API body/role flags. In particular,
globalAdmin and webMfa must come from current auth/session authority, not a request
claim. Membership uses the current tenant-service snapshot; catalog kind corresponds
to the entitlement plan version. The delivered `GrpcMembershipAccess`,
`GrpcEntitlementSource` and versioned projection are available building blocks.
Domain handlers still own concrete adapters and transaction-bound checks.

## Versioned source composition — TAS-334

`VersionedCapabilitySource` composes `PlatformConfigurationSnapshotCache` and
`EntitlementProjection` with a mandatory online `Authority`. Each subscription first
authorizes the actual verified actor, credential/grant, membership and target ACL,
then obtains source data and calls a request-local `Assembler`. The assembler resolves
catalog kind against the supplied immutable entitlement plan version and supplies
current lifecycle, integrity, accepted-operation and notification purpose facts.
No actor/role/grant/channel decision is stored in either cache. Tenant/source versions
form `Inputs.Key(tenant, flagsVersion, entitlementVersion)`; the platform source is
shared globally because company overrides are forbidden, while commercial cache
entries are keyed by tenant and entitlement version. A/B updates remain independent.

The default `Source.current` is for commands: both flags and billing are reconciled
online even with warm caches. `observations()` is explicitly for display/read policy
and can reuse hard source leases of at most30 seconds. Do not inject it into mutation,
secret issuance, download or dequeue admission. Fact expiry is bounded by both source
deadlines, fresh authority and the signed credential expiration. Facts are bound to
the actual context, actor, request and target; a tenant mismatch fails before source
reads. An empty/missing/untrusted mandatory source returns unavailable, never an
implicit FREE plan or an enabled flag. A capability remains advisory; domain owners
still serialize/persist the exact accepted operation and policy versions for D86.

`PlatformConfigurationSnapshotCache` reads the complete allowlisted scalar registry
through the service-only `PlatformService.GetPlatformConfigurationSnapshot` RPC and
`GrpcPlatformConfigurationSource` with production mTLS/JWS and a five-second deadline.
The version is a deterministic hash of the complete sorted versioned configuration,
not the maximum per-key version. Defaults, types, bounds and effectiveAt are validated;
unknown/private keys, missing entries, conflicting equal versions, older replies or
future-effective values fail closed. Failed primary reconciliation clears the warm
entry. Reads, duplicate events and reset do not extend a lease. Version fences survive
memory clear; a new process must reload the primary before serving a cold cache.
Failure cleanup is bound to the read's captured generation and warm entry: an old
failed request cannot erase a newer primary confirmation or invalidate reads started
after an event. Equal-version effectiveAt and other persisted metadata are immutable;
changes require a new source version, just as value changes do.

An explicitly global target has no entitlement or tariff (`PlanKind.UNKNOWN`), and its
assembler must handle absent commercial input. The existing evaluator's global policy
decides account/platform actions; tenant actions still require an actual selected
tenant. A global request never borrows a paid company's cached plan.

`invalidate(Change)` is an authenticated version fence; event payloads never supply
replacement configuration values. It requires an explicitly global SERVICE context,
action `applyPlatformConfiguration`, scope `configuration:invalidate`, and a supplied
`EventAuthority` that validates actual broker/source identity and purpose. Old/duplicate
versions cannot roll back state or renew TTL; an in-flight pre-invalidation reply is
discarded. The event delivery adapter must extract the key/version from the committed
TAS-462 outbox payload and await this Mono before acknowledging. Shared broker delivery
and concrete primary domain authority adapters remain deployment/domain integration
work; no permissive bean or legacy route activation is introduced here.

The existing evaluator still controls mandatory financial/security notifications:
ordinary EMAIL/TELEGRAM/INBOX flags do not disable an independently authorized control
message. Public REST capabilities and its action registry are TAS-497, not a second
policy matrix in this source library.

## Policy decisions

The decision order preserves authentication, credential activity/generation, current
scopes, channel, administrative scope/level, resource resolution and integrity before
business exceptions. TENANT_ADMIN inherits project ADMIN without explicit project
membership. A project role grants rights only in its exact tenant/project. An ordinary
member with no project ACL gets RESOURCE_NOT_FOUND. Owner financial rights remain
separate from tenant administration.

An administrative exception requires current GLOBAL_ADMIN plus a completed current
MFA web session, or a current nonrevoked/unexpired ADMIN_PAT/ADMIN_OAUTH grant matching
actor, grant generation, signed scope, level and the target. TENANT_SET never grants
global-account/platform access. Global account administration also requires
manage_global_accounts. An administrative collection query carries an explicit
`Target.selectedTenants` subset (at most100); it must fit the current grant. An empty
selector with no single tenant does not mean all companies for a TENANT_SET grant.
The query owner must bind that exact effective set before SQL count/page/row access.
READ level does not create export jobs or modify data.
User PAT/OAuth never inherit admin bypass, including tokens owned by GLOBAL_ADMIN.
Grant issuance/expansion remains MFA web only, including for service workers.

`Decision.businessBypass` is true only for a successfully authorized administrative
exception. `auditRequired` is also true for applicable denied administrative attempts;
it is not evidence of bypass. The owning handler must persist the internal audit with
the real verified actor and retain ordinary attribution. An allowed administrative
exception also returns `bypassedRestrictions` for backend audit; these causes remain
active in company state. Public `Capability.restrictions` describes reasons actually
denying that action, so allowed admin actions do not pretend to be denied. Policy evaluation itself has
no audit/database side effects. Quota owners must count actual usage even under a
valid bypass. A public Capability is informational and is not a reusable authorization
token; every mutation evaluates current authority again.

Integrity results cannot classify QUOTA_EXCEEDED, READ_ONLY or CAPABILITY_DISABLED as
hard invariants. Business results have a closed set of bypassable codes; version,
idempotency, confirmation, protected owner/last-admin, financial evidence, validation
and dependency failures cannot be placed there. Domain authorities supply those
checks for their actual operation. Missing checks fail DEPENDENCY_UNAVAILABLE. The
evaluator additionally prevents self-delete of a known owner and new content writes
into deleting/deleted tenants, including for an administrative exception.

Independent restrictions are unioned with the complete entitlement. Clock expiration
is rechecked on every decision. A new paid version never clears independent pause,
block or another dispute. Reasons use the matrix order: platform block, owner pause,
payment dispute, pending first payment, expired payment, over quota. Missing commercial
terms deny new content writes; permitted ACL reads and explicit financial recovery do
not become new paid rights. There is no FREE/unlimited fallback.

Financial READ_ONLY uses the explicit policy profiles, not a generic usage-reducing
rule: owner payment/plan/refund recovery, tenant-admin member removal/invite revoke/
project deletion, self leave and an old valid invitation remain possible under the
applicable causes. Individual attachment/comment/issue deletion remains a content
write. Pause/block/dispute remain independent prohibitions for the affected recovery
profiles. Content reads during pause/block use READ_WHILE_RESTRICTED (catalog default
true); status/control/financial recovery and authorized control notifications remain
distinct from project payload. D244 allows an owner to transfer/delete during owner
pause; platform block/dispute forbid both. Transfer additionally needs active paid
coverage and its flag. Account self-delete ignores tenant restrictions while retaining
global ownership/last-admin/purpose-code safety.

Global flag absence is a dependency failure, distinct from false. MCP and user-PAT
flags intersect with the corresponding FREE flags for the target company's actual
kind; another paid company grants nothing. A durable `Accepted` owner receipt can pin
only its admitted action/operation/target and action feature flag. It never bypasses
current credential/scope/MCP/PAT restrictions. An old WIRE invoice and accepted upload
can therefore finish according to their owner lifecycle after the action flag changes.
Service workers need explicit current issuer/purpose/scope authority plus a durable
admitted receipt bound to the exact operation and target; service-name text alone is
insufficient. Owners must recheck current requester ACL for export/delivery and retain
lease/generation/audit constraints. Global notification and cleanup protocols keep
their own explicitly global authority; company actions require an actual tenant.

Activation in company/member, project/workflow and issue/file handlers is deliberately
separate: TAS-491, TAS-493, TAS-495; PAT/OAuth/MCP adapters are TAS-463. This task does
not add those public routes or supply fabricated primary authorities. Source adapters
must enforce all domain conditions in the matrix, including financial evidence,
purpose tokens, exact target ownership and version/confirmation rules. Domain mutation
locks, atomic quota admission and transactional outbox remain with their owners.

Run `python3 scripts/harness.py check --module grpc-common-lib` with Docker available.
Required policy tests cover recovery exceptions, D244, role/ACL, revoked current
authority, grants, false/unknown flags, exact receipt pinning, clock, no default paid
rights and preserved integrity. The real mTLS/JWS test verifies that payload role,
USER_PAT, ADMIN-kind without a primary grant and bare service identity cannot obtain
administrative rights. These isolated checks do not establish stage/public API E2E.
