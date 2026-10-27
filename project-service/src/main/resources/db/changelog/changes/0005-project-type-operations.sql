--liquibase formatted sql
-- changeset taska:0005-project-type-operations
CREATE TABLE taska.project_type_operations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES taska.projects(id),
    kind text NOT NULL CHECK (kind IN ('DELETE_TYPE','WORKFLOW_BINDINGS','CREATE_TYPE')),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO taska.project_type_operations(id,project_id,kind,active)
SELECT id,project_id,'DELETE_TYPE',state IN ('PENDING','CANCELLING') FROM taska.issue_type_deletions;
CREATE UNIQUE INDEX project_type_one_active_operation ON taska.project_type_operations(project_id) WHERE active;
ALTER TABLE taska.project_issue_types DROP CONSTRAINT project_issue_types_pending_operation_id_fkey;
ALTER TABLE taska.project_issue_types ADD CONSTRAINT project_issue_types_pending_operation_id_fkey
    FOREIGN KEY(pending_operation_id) REFERENCES taska.project_type_operations(id);

CREATE TABLE taska.issue_type_creations (
    id uuid PRIMARY KEY REFERENCES taska.project_type_operations(id),
    project_id uuid NOT NULL REFERENCES taska.projects(id),
    actor_user_id uuid NOT NULL,
    request_id text NOT NULL,
    node_id text NOT NULL,
    command_hash text NOT NULL,
    code text NOT NULL,
    generation uuid NOT NULL,
    name text NOT NULL,
    color text NOT NULL,
    icon text,
    shape text NOT NULL,
    sort_order integer NOT NULL,
    desired_workflow_id uuid,
    first_type_code text NOT NULL,
    first_type_generation uuid NOT NULL,
    resolved_workflow_id uuid,
    channel text,
    token_id uuid,
    state text NOT NULL CHECK(state IN ('PENDING','CANCELLING','COMPLETED','CANCELLED')),
    error_status text,
    error_message text,
    error_details jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(project_id,actor_user_id,request_id)
);
CREATE INDEX issue_type_creation_recovery ON taska.issue_type_creations(updated_at) WHERE state IN ('PENDING','CANCELLING');
