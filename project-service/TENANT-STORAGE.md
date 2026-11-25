# Project tenant storage — TAS-329

The `tenant-storage` Spring profile supplies the B2B persistence layer. The default profile continues serving the deployed pre-tenant API. It does not assign existing projects to an arbitrary tenant. The [native API and quota/ACL integration](TENANT-API.md) is supplied by TAS-340; irreversible deletion is TAS-459. Enabling this profile on the shared stage is not part of this change.

## Database and activation

Provision a **new dedicated project database** with the [TAS-308 role bootstrap](../docs/data/tenant-rls-templates.md), using `service=project`, `schema=taska`. Provision separate app/deployer passwords in secret storage. The bootstrap administrator also creates `project_liquibase` owned by `taska_project_migrator` and revokes PUBLIC access to that schema. Runtime uses `taska_project_app`; Liquibase logs in as `taska_project_deployer` and explicitly sets the NOLOGIN migrator role. Startup rejects privileged runtime roles, role memberships and runtime table ownership.

Use the ordinary `spring.r2dbc.*` and `spring.liquibase.url/user/password` configuration (the existing `PROJECT_SPRING_*` environment variables) with `SPRING_PROFILES_ACTIVE=tenant-storage`. The profile fixes its changelog to [db/project-tenant/changelog.json](src/main/resources/db/project-tenant/changelog.json); the default legacy changelog stays unchanged. No shared database reset, data deletion or guessed tenant backfill is performed. The clean-start changelog refuses any existing business tables. A second invocation on its own migrated database is a no-op through Liquibase history.

The new changelog creates the final pre-tenant table shape in an empty schema, then adds tenant constraints and applies/audits the shipped TAS-308 templates, atomically in one changeset. It does not replay or rewrite applied legacy changesets. UUID primary identities remain globally unique. `project_key` is unique within a tenant. Members/settings/types and journal/operation references use composite tenant FK; a pending type fence additionally includes the project identity. The eight business/outbox tables use UUID NOT NULL `tenant_id`, ENABLE/FORCE RLS and tenant-leading lookup/recovery indexes. There is no tenant default or administrator bypass flag.

Outbox app access is append-only; global discovery, dispatch leases and event-envelope rollout remain TAS-318/TAS-489. Legacy repositories/RPC implementations and outbox schedulers are excluded in this profile. TAS-340 activates the registered project RPCs and the explicit tenant recovery worklist only when managed transport/current authority are configured; other methods stay closed. The new creation/authorization tables and grant-generation column have their own additive changeset. This is not a claim of deployed native traffic.

Deploy activation requires coordinated gateway/owner-service tenant propagation, current membership/capability/project ACL checks and tenant-scoped workers. Do not point the legacy profile at the new database. Rollback before traffic selects the previous application/database configuration; after traffic, preserve the tenant database and use a coordinated forward recovery. No automatic down migration or export to global tables exists.

## Transaction and repository contract

`TenantProjectStore.execute(session -> Mono<result>)` obtains the tenant exclusively from `VerifiedTenantContext` and uses `TenantTransactionExecutor` TAS-307. Compose all local SQL changes inside that callback; collect lists before returning. No remote calls, detached subscriptions or caller-supplied tenant IDs are accepted. The callback result is emitted after commit. Nested same-identity calls join the same connection; escaped sessions cannot perform SQL outside the owning transaction.

The session exposes projects/key lookup/member list/batch metadata, optimistic updates, members, settings, registry types, project operation gates and creation/deletion journals. Every native lookup, join, mutation and recovery query has an explicit verified tenant predicate or bound insert tenant, in addition to RLS. This API is an internal storage boundary; tenant capability/last-admin/input policy is enforced by the native TAS-340 command layer, rather than by treating possession of a project UUID as authorization.

Operation/journal creation retains the verified actor and positive actor/membership generations, optional grant ID, and initial operation fence. The generations are needed for subsequent **current** authority revalidation; the stored snapshot does not authorize recovery by itself. Journal actor mismatch is rejected. Recovery discovery only reads the current tenant's rows and performs no background impersonation. Service-only work without a human actor cannot invent a journal identity.

Archive preserves the existing project ADMIN check and project-before-member lock order. It updates the project and appends one `ProjectArchived` event in the same transaction; retries are idempotent. Reads by ID remain possible after archive, active lists exclude the project, and `countProjects` still includes it. A database trigger closes physical deletion until TAS-459. Archive does not implement quota reservation/release; keeping archived rows/counts prevents treating it as deletion.

## Verification

```sh
python3 scripts/harness.py check --module project-service
```

`TenantProjectStorageIntegrationTest` is a required fresh nonempty report. It uses isolated PostgreSQL16, shipped bootstrap/protection/audit SQL, a real app LOGIN and R2DBC pool. It checks migration/repeat/startup, A/B same-key creation and concurrent duplicates, FK/RLS/missing context, batch and journal isolation, atomic rollback, archive ADMIN/idempotency/outbox/count behavior, disabled legacy beans and selective query plans on representative data without planner overrides. Existing project-service checks and explicit creation/deletion IT remain required. These storage tests do not prove public REST/RPC or deployment. The separate [native project E2E](TENANT-API.md#verification) exercises the coordinated API/workflow subset; global dispatch and shared-stage readiness remain outside that proof.
