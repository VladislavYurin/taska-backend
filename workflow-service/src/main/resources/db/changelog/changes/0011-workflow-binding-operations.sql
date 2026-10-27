--liquibase formatted sql

--changeset taska:0011-workflow-binding-operations
ALTER TABLE taska.workflows ADD COLUMN owner_project_id uuid NULL;
-- A graph shared across projects or an old orphan has no inferred owner and stays fail-closed.
UPDATE taska.workflows w SET owner_project_id=owners.project_id
FROM (SELECT workflow_id,(array_agg(DISTINCT project_id))[1] AS project_id
      FROM taska.workflow_bindings GROUP BY workflow_id HAVING count(DISTINCT project_id)=1) owners
WHERE w.id=owners.workflow_id;
CREATE INDEX workflows_owner_project_idx ON taska.workflows(owner_project_id,created_at,id);

CREATE TABLE taska.workflow_binding_changes (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('CREATE','REPLACE_TYPES')),
    request_id text NOT NULL,
    node_id text NOT NULL,
    channel varchar(32) NULL CHECK (channel IS NULL OR channel IN ('UI','API_TOKEN','MCP')),
    token_id uuid NULL,
    command_json jsonb NOT NULL,
    plan_json jsonb NOT NULL,
    state text NOT NULL CHECK (state IN ('PENDING','CANCELLING','COMPLETED','CANCELLED')),
    error_status text NULL,
    error_message text NULL,
    error_details jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_bytes bytea NULL,
    reservation_released boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(project_id,actor_user_id,request_id)
);
CREATE UNIQUE INDEX workflow_binding_changes_pending_project
    ON taska.workflow_binding_changes(project_id) WHERE state IN ('PENDING','CANCELLING');
CREATE INDEX workflow_binding_changes_recovery
    ON taska.workflow_binding_changes(updated_at,id) WHERE reservation_released=false;

CREATE TABLE taska.issue_type_creation_workflow_receipts (
    operation_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('APPLIED','CANCELLED')),
    workflow_id uuid NULL,
    code text NULL,
    generation uuid NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
