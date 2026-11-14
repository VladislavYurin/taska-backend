# Native issue CRUD — TAS-343

The optional `tenant-storage` profile implements the existing IssueService CreateIssue,
GetIssue/GetIssueDetails/GetIssueByKey, UpdateIssue/PatchIssue, AssignIssue, TransitionIssue
and DeleteIssue contracts when managed mTLS/JWS trust is configured. Default/legacy behavior
is unchanged. This does not enable native storage on a shared environment.

## Authority

Only the recipient's message-bound verified context supplies tenant, actor, credential and
scopes. Payload reporter/actor must match it. Each operation reads current auth/root membership,
project ACL/archive state, platform configuration and billing facts. A company administrator
receives the effective project role from project-service; a global role string never establishes
project access or a business bypass. The common capability evaluator retains scoped/MFA rules.

Project keys and initial status are obtained from their owners. Without an explicit status,
the first TODO-category workflow status by sort order/id is used, including custom keys.
Transitions are validated against the current project/type workflow and source status. An
assignee requires current ACTIVE root membership and either a current project grant or the
company administrator's effective project rights. UUID/key reads have explicit tenant predicates;
missing/deleted keys never fall back to a global issue lookup.

Identity/project/capability primary reads precede mutation SQL. Workflow and target-membership
checks are composed within the owner transaction before persistence, with bounded RPC deadlines.
The bounded authority permit is bound to tenant, actor,
action and project, and checked again after waiting for the issue lock. Lifetime create
admission additionally rechecks primary authority and current billing under its existing quota
lock. No guessed tenant, synthetic user or permissive default authority is configured.

## Transactions and retries

Create retains the TAS-332/TAS-460 owner transaction and writes issue, display numbering,
lifetime admission, history, issue outbox, quota outbox and receipt together. Create uses
`Idempotency-Key`. Other mutations use a stable `X-Request-Id`; retries must preserve it and
all body fields. The exact native response is stored with the receipt. Replays reauthorize the
current requester but do not rerun a transition against its already-changed source status or
increment version/history/outbox/quota again. Reusing the key with another body conflicts.

Mutations claim the actor/action-scoped receipt and lock the tenant-owned issue row. PATCH and
versioned transitions check the supplied version; the legacy mutation shapes use a compare-and-set
against the preflight version. Concurrent changes cannot overwrite a newer row. Issue mutation,
version, metadata-only history/outbox, authorization audit and receipt commit atomically. Soft
delete hides the issue without removing history or releasing lifetime usage.

The new additive migration creates FORCE-RLS, append-only `issue_authorization_audit` for actual
administrative bypasses. Actor, target, action, request correlation and closed bypass reasons
are recorded. Payload text, credentials and author profile snapshots are not written there.
Existing applied changesets are unchanged.

History is read with its issue row locked so returned state/history are consistent. Profiles of
historical authors are not globally enriched. Native details return core fields and history;
optional comment/watch/link/file facets remain absent until their separate native domain tasks.
Type-changing PATCH is refused until the native type-move protocol is activated, rather than
silently rewriting workflow bindings. Search/board, nested-domain handlers and workflow status
migration remain separate activation boundaries (TAS-344/362/363/364/365 and related tasks).

## Task usage in project reads

`GetProjectTaskQuotas` accepts at most 100 project IDs, authorizes the complete set using one
current owner batch read, and loads counters with one tenant-qualified SQL query. An accessible
project with no counter has zero creations; an unavailable authority never becomes zero. Usage
is `lifetime_created`, including deleted issues, with the current numeric limit or `unlimited`.
The native count batch uses the same read boundary and supports its type/deletion filters.

The gateway composes `taskQuota {used, limit?, unlimited}` into project context and project stats.
Legacy responses may omit this optional additive field. Native failures propagate; unknown
usage is not fabricated. A create quota refusal carries `quota {kind, limit, used}` through gRPC
trailers into the REST problem response.

Exact managed service edges are required from issue-service to root membership/configuration,
billing entitlement/referenced catalog and project/workflow reads. Referenced catalog access
uses SERVICE purpose `billing`, scopes `read/catalog:read`; this grants neither offer resolution
nor catalog mutation. Preserve operator-managed trust and update all consumers of the additive
proto before enabling the routes. Existing database role/bootstrap requirements are documented
in [TENANT-STORAGE](TENANT-STORAGE.md); lifetime semantics in [TASK-QUOTAS](TASK-QUOTAS.md).

## Verification

```sh
python3 scripts/harness.py check --module issue-service
python3 scripts/harness.py check --module api-gateway
python3 scripts/harness.py check --module billing-service
python3 scripts/harness/issue_acl_e2e.py
```

Storage integration tests use actual PostgreSQL, app LOGIN/RLS and shipped migrations, including
concurrent updates, stale/expired authority, retries and rollback after outbox/audit failure.
The owned E2E packages seven applications with managed mTLS/JWS and fresh databases. It checks
REST/direct RPC, foreign IDs, project roles, archived writes, revocation, lifetime reads and
receipt/history behavior. Its signed cleanup affects only its own resources. It does not establish
shared-stage activation, Kafka delivery, external payments or unimplemented native domains.
