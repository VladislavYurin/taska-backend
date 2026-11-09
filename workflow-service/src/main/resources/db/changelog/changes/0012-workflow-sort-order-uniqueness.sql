--liquibase formatted sql

-- changeset taska:0012-workflow-sort-order-uniqueness runInTransaction:true
-- preconditions onFail:HALT onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM (SELECT 1 FROM taska.transitions GROUP BY workflow_id, sort_order HAVING COUNT(*) > 1) duplicates
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM (SELECT 1 FROM taska.statuses GROUP BY workflow_id, sort_order HAVING COUNT(*) > 1) duplicates
-- comment: Duplicate display order is not duplicate graph data. Stop before changing any rows/indexes.

-- Existing conflicts require an operator to assign distinct sort_order values while retaining
-- every status ID/key, transition endpoint and validator rule. A status without local edges may
-- still be referenced by issue-service. Never DELETE graph rows to repair display order.
-- Diagnose each table with: SELECT workflow_id,sort_order,array_agg(id) FROM taska.statuses
-- GROUP BY workflow_id,sort_order HAVING COUNT(*)>1; repeat for taska.transitions.
CREATE UNIQUE INDEX statuses_workflow_sort_order_uniq ON taska.statuses (workflow_id, sort_order);
CREATE UNIQUE INDEX transitions_workflow_sort_order_uniq ON taska.transitions (workflow_id, sort_order);
