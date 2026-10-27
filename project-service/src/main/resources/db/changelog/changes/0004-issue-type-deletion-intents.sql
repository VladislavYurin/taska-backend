--liquibase formatted sql
-- changeset taska:0004-issue-type-deletion-intents
CREATE TABLE taska.issue_type_deletions (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES taska.projects(id),
    source_code text NOT NULL,
    source_generation uuid NOT NULL,
    target_code text,
    target_generation uuid,
    actor_user_id uuid NOT NULL,
    status_mappings jsonb NOT NULL DEFAULT '{}',
    state text NOT NULL CHECK (state IN ('PENDING','CANCELLING','COMPLETED','CANCELLED')),
    request_id text NOT NULL,
    node_id text NOT NULL,
    channel text,
    token_id uuid,
    reservation_released boolean NOT NULL DEFAULT false,
    error_status text,
    error_message text,
    error_details jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((target_code IS NULL) = (target_generation IS NULL))
);
CREATE UNIQUE INDEX issue_type_one_pending_deletion_per_project
    ON taska.issue_type_deletions(project_id) WHERE state IN ('PENDING','CANCELLING');
CREATE INDEX issue_type_deletion_lifecycle_idx ON taska.issue_type_deletions(project_id,source_code,source_generation);
ALTER TABLE taska.project_issue_types ADD COLUMN pending_operation_id uuid REFERENCES taska.issue_type_deletions(id);
CREATE INDEX issue_type_deletion_recovery_idx ON taska.issue_type_deletions(updated_at) WHERE reservation_released=false;
