# Native workflow API — TAS-342

The `tenant-storage` profile serves the existing WorkflowService wire contract through native
adapters when managed trust is configured. This does not activate a shared environment.
Database setup and the immutable company template model are described in [TENANT-STORAGE](TENANT-STORAGE.md).

## Authority and scope

Each invocation verifies the current Auth credential, Root membership, project access and current
type registry before workflow SQL. Payload actor/role fields cannot establish authority.
TENANT_ADMIN receives the effective project ADMIN role from the project owner. Stale membership
or type generations, archived projects and unavailable primaries cannot authorize new writes.

The shared capability evaluator reads current platform flags, billing entitlement and the exact
referenced plan kind. Configure exact managed trust edges from workflow-service to Root membership
and configuration, billing entitlement/referenced catalog, and project access/type reads. The
billing catalog allows workflow-service to read a referenced version with service purpose `billing`
and `catalog:read`; it does not grant offer resolution or catalog mutation authority.

`WORKFLOW_CONFIGURATION` controls graph settings on FREE and paid plans. Binding changes also
require `ISSUE_TYPE_CONFIGURATION`. Existing graph reads and transition validation continue when
configuration is disabled. An administrative exception requires actual current authority and web
MFA, and records a tenant-scoped authorization audit. User PAT/OAuth do not inherit that exception.

Every graph belongs to the selected tenant and a real project. Nested status/transition IDs must
belong to that graph. Bindings use the current registry generation with no shared/zero-project or
foreign fallback. Runtime reads hide hidden transitions; editor reads include them and group
shared bindings. The first registry type determines `mainWorkflowId`. Transition validation retains
the implemented source/target checks. DONE semantics follow the category, independently of its key.

## Transactions, retries and migration boundary

Reads take a shared tenant/project transaction lock; writes take its exclusive counterpart,
reject pending type/binding operations and pins, then lock the owned workflow. Optional versions
are checked under that lock; binding replacement requires a version. Graph validation, version
increment and local bindings commit together. No primary RPC runs inside this SQL transaction.
An authorization lease that expires while waiting cannot authorize a new write.

Create records a deterministic command hash and exact response in `workflow_creation_receipts`,
atomically with graph publication. Retry the same command and `X-Request-Id` after a timeout.
Changed payloads conflict; replay still requires current requester authority. A completed receipt pins only its action feature flags, allowing an exact response replay after configuration is disabled without admitting a new graph. Receipts contain no
credentials. Metadata/status/transition edits preserve IDs and enforce TODO, at least one visible
edge, valid categories, unique keys/orders/edges and no self-transition.

Binding changes preserving every source status key publish without moving issue data. Removing a
binding or deleting a workflow restores a fresh graph from the company's immutable DEFAULT copy
when its keys cover the source graph. A foreign or mutable project graph is never used as fallback.
Retired graphs remain inactive, retaining historical template-instance identity.

Native issue CRUD/migration activation belongs to TAS-343. A request requiring issue status changes
cannot infer zero usage from an unavailable owner. Status mappings, deleting a status from a bound
graph, or a destination missing source keys return `UNAVAILABLE`, reason
`WORKFLOW_MIGRATION_UNAVAILABLE`, with all local effects rolled back. These status transfers and
later project/type operation activation TAS-493 are not claimed here. Existing journal/pin and
generation fences remain enforced; no global recovery scan or fabricated recovery actor is enabled.

The additive migration creates append-only, RLS-protected creation receipts and authorization
audit, and enforces status/transition order uniqueness. Applied changesets remain intact.
The gateway binds every mutation publisher to the verified outbound context, including direct
metadata/status/transition/delete RPCs.

## Verification

```sh
python3 scripts/harness.py check --module workflow-service
python3 scripts/harness.py check --module billing-service
python3 scripts/harness/workflow_acl_e2e.py
```

`TenantWorkflowApiIntegrationTest` uses real PostgreSQL/RLS with doubles at primary boundaries.
The owned E2E packages seven applications with native databases and managed mTLS/JWS, checks REST
and direct RPC isolation/current roles/configuration-off runtime, then cleans its signed run state.
Existing storage integration tests, explicit workflow IT and affected legacy regressions remain
required. These checks do not establish shared-stage activation, native issue status migration,
notification delivery or payments.
