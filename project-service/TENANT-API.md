# Native project API (TAS-340)

The `tenant-storage` profile serves the existing ProjectService create/get/list/update/context, membership, role/key, issue-type read and batch/stats RPCs through `TenantProjectApi`. The default legacy profile and its transport contract remain available. This implementation does not activate a deployed environment or migrate legacy data.

The public contract remains [OpenAPI](../api-gateway/src/main/resources/static/openapi.yml). Create accepts an optional canonical UUID `Idempotency-Key`; the gateway forwards it through the additive proto field. Without it, the verified request ID identifies the operation. Native clients retry the same payload with the same key, including after a timeout; a different payload conflicts. The gateway has separate configurable budgets for coordinated creation (`taska.projects.creation-deadline`, default `15s`) and context aggregation (`taska.projects.context-deadline`, default `10s`). Other project calls retain their configured deadline.

## Authority and data boundaries

Every invocation obtains current credential facts from Auth and current membership/lifecycle from Root before project SQL. Platform flags and the current entitlement/reference plan feed the shared `CapabilityEvaluator`. An individual Root membership generation, not the tenant collection cursor, binds the project grant. Target project lookup and actor role validation share the project transaction; mutations lock the project before inspecting grants. Collections filter the selected tenant and visible grants before returning or counting rows.

An ACTIVE tenant administrator/owner has effective `ADMIN` on every project in that company without an explicit grant. Only a current company administrator can create a project through ordinary credentials. A project administrator can manage project members but cannot create a project. An account-wide role in a request body/Header never establishes authority: the administrative exception uses the actual Auth role and actual web MFA, and records an authorization audit. Unsupported credential kinds remain closed by primary Auth authority.

Explicit project grants carry `root_membership_version`. Suspension/removal/reactivation or a Root role-generation change invalidates older grants; re-adding an ACTIVE same-company member records the new generation. Existing unversioned foundation grants are not assigned a guessed generation. Member/stats enrichment omits inactive or stale grants and uses a bounded private Auth profile read after primary membership checks. Profile DTOs retain optional fields; this narrow path supplies display name/email and does not activate the global native directory.

Context includes the real type registry, workflow graph and project labels. A downstream failure fails the aggregate instead of fabricating empty facets. The private workflow/labels reads independently validate the delegated caller's current project access. Ordinary workflow CRUD, archive/restore and issue/label CRUD remain separate TAS-342, TAS-341 and TAS-343 work; type mutation activation is separate TAS-493 work.

## Creation and recovery

`project_creation_operations` freezes actor/membership generation, request hash/body, operation/project identity and reservation deadline before remote reserve. Root independently authorizes new admission and atomically enforces the PROJECT quota. Creation then:

1. Materializes a hidden `PROVISIONING` project under its exact Root reservation/fence.
2. Seeds settings and default type generations, clones the company's provisioned DEFAULT template and binds its complete workflow graph to those generations. Missing company provisioning is a rejection, not an empty graph or global fallback.
3. Persists initialization, commits the existing Root reservation and opens the owner aggregate. `ProjectCreated` and the final local operation state commit atomically with opening. Internal provisioning events are not substitutes for this domain event.

All ordinary reads exclude hidden projects. Retries reuse the frozen resource, operation and expiry, without another slot. A confirmed failure retains durable cancellation intent before remote compensation; cancellation removes workflow preparation and local members/types/settings/project, while terminal receipts survive. An uncertain result retains the reservation. A commit/cancel race either completes that committed resource or cancels the existing reservation; it cannot create another operation. Current user authority is checked again on replay, including after loss of company administration.

`ProjectCreationRecovery` is a bounded, explicit operator worklist. `taska.projects.recovery-tenants` defaults to empty and accepts at most 100 configured tenant UUIDs; `taska.projects.recovery-interval` defaults to `30s`. Discovery uses the app LOGIN/RLS and rotates up to 100 pending operations using `recovery_checked_at`. Each continuation obtains a new managed context with purpose `provisioning`, identity `project-service` and scopes `write,project:recover`. Root denies that worker new reserve/replay admission. It can finish the same accepted resource or cancel it, including when the initiating credential has been revoked. Inventory reconciliation carries the accepted reservation and checks its primary identity/fence; it does not grant unbound service reads. The worker never invents a human actor or uses a zero UUID. Debug diagnostics contain status codes, not request payloads or credentials.

Archive keeps quota usage; irreversible deletion/release of committed usage belongs to TAS-459. Native outbox rows are appended here; cross-service publication/dispatcher activation is a separate boundary and is not proved by a row-count assertion.

## Runtime activation

Use the [dedicated database/profile setup](TENANT-STORAGE.md), coordinated with native workflow/issue databases, Auth's native profile and Root/Billing primaries. Company provisioning TAS-337 must have created its private workflow template copy. Applied changesets remain unchanged; the new project/workflow/labels migrations are additive.

Managed mTLS, trust snapshots and current primary authority are required. `taska.domain.<service>.host/port` selects raw mTLS channels. Defaults are `auth-service:9091`, `tenant-service:9099`, `billing-service:9100`, `project-service:9096`, `workflow-service:9093`, `issue-service:9094`. These channels do not use legacy claim-minting interceptors. Configured Root membership readers must include the project, workflow, auth and Root services that perform their respective primary reads.

Provision exact managed trust edges, without wildcard rules:

- Original user delegation: gateway/dependent services to the registered project RPCs; project/workflow to quota authority; Root to owner inspect/cancel/open/inventory; project to workflow initialization/read and labels read; workflow to project initialization describe/access/type reads; issue to project access checks.
- Primary service reads: membership purpose to Root batch validation; billing purpose to entitlement/reference-plan reads; admin purpose to the public configuration snapshot; project membership purpose to Auth's bounded project profiles (`memberships:read,profiles:read`).
- Recovery: `provisioning` / `project-service` identity for self Recover and its delegated quota, initialization and owner paths, including inventory. The issuer changes at each hop; the service identity/purpose and reduced scopes remain bound to the original managed subject.

The owned fixture is an executable edge inventory in [project_acl_e2e.py](../scripts/harness/project_acl_e2e.py). Configure the production operator worklist and service edges before enabling traffic; this document does not grant permission to switch or restart a shared stand.

## Verification

```sh
python3 scripts/harness.py check --module project-service
python3 scripts/harness/project_acl_e2e.py
```

The dedicated E2E packages seven real applications, creates owned native PostgreSQL/RLS databases and mTLS/JWS material, and destroys its own resources through the signed harness state. It checks REST and direct RPC tenant isolation, batch/stats, current grants, implicit tenant administration, real MFA/audit, current-role create replay, one-slot admission, full graph initialization, confirmed compensation and unknown-outcome recovery after initiating-credential revocation/flag-off. It does not prove shared-stage rollout, full workflow/issue CRUD, Kafka delivery or real payments. Run the ordinary legacy trusted-RPC regression separately when changing shared transport.
