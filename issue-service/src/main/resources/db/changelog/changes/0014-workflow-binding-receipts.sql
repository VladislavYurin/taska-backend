--liquibase formatted sql
-- changeset taska:0014-workflow-binding-receipts
-- Unlike type deletion receipts, these are transient write fences, not generation tombstones.
CREATE TABLE taska.workflow_binding_receipts (
    operation_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    state text NOT NULL CHECK(state IN ('APPLIED','CANCELLED')),
    changed_count bigint NOT NULL CHECK(changed_count>=0),
    scope jsonb NOT NULL,
    released boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX workflow_binding_active_fences ON taska.workflow_binding_receipts(project_id) WHERE state='APPLIED' AND NOT released;
