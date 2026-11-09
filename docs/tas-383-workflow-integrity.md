# Workflow integrity — TAS-383

Project context authorizes project membership before reading workflow data. A failure for one
workflow type is logged by status code and omits that type, retaining the available project,
members, labels and other workflows. Failures of the primary project/member/label sources
still propagate. Creation/edit validation requires statuses, TODO and a visible transition.

Migration 0012 adds unique `(workflow_id, sort_order)` indexes to statuses and transitions.
It never deletes or rewrites graph data. Both preconditions run before either index: existing
duplicate display orders HALT migration. Before rollout, inspect duplicate groups in both tables:

```sql
SELECT workflow_id, sort_order, array_agg(id)
FROM taska.statuses GROUP BY workflow_id, sort_order HAVING count(*) > 1;
SELECT workflow_id, sort_order, array_agg(id)
FROM taska.transitions GROUP BY workflow_id, sort_order HAVING count(*) > 1;
```

For each affected workflow assign distinct display orders under the operator's normal change
procedure, preserving every status ID/key, transition and validator rule, then retry Liquibase.
No automatic deletion is safe: issue-service may reference a status that has no local edges.
No shared data repair is performed by the tests or application startup.

`WorkflowSortOrderMigrationTest` uses PostgreSQL/Liquibase to check both duplicate cases preserve
all data and install no partial indexes, plus successful upgrade/replay and unique constraints.
`ProjectServiceImplTest` checks partial workflow failures and retained primary-source errors;
`WorkflowEditIT`, `WorkflowCreationIT` and `WorkflowServiceIT` cover graph reads and mutations.
