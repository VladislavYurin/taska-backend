--liquibase formatted sql
-- changeset taska:0010-issue-type-move-pins
CREATE TABLE taska.issue_type_move_workflow_pins (
    operation_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    valid_status_keys text[] NOT NULL,
    released boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX issue_type_one_workflow_pin_per_project
    ON taska.issue_type_move_workflow_pins(project_id) WHERE released=false;
