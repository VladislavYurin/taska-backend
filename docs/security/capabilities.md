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
manage_global_accounts. READ level does not create export jobs or modify data.
User PAT/OAuth never inherit admin bypass, including tokens owned by GLOBAL_ADMIN.
Grant issuance/expansion remains MFA web only, including for service workers.

`Decision.businessBypass` is true only for a successfully authorized administrative
exception. `auditRequired` is also true for applicable denied administrative attempts;
it is not evidence of bypass. The owning handler must persist the internal audit with
the real verified actor and retain ordinary attribution. Policy evaluation itself has
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
