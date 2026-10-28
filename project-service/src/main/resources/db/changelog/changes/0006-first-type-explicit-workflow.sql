--liquibase formatted sql
-- changeset taska:0006-first-type-explicit-workflow
ALTER TABLE taska.issue_type_creations ALTER COLUMN first_type_code DROP NOT NULL;
ALTER TABLE taska.issue_type_creations ALTER COLUMN first_type_generation DROP NOT NULL;
ALTER TABLE taska.issue_type_creations ADD CONSTRAINT creation_default_snapshot_required CHECK (
    (first_type_code IS NOT NULL AND first_type_generation IS NOT NULL)
    OR (first_type_code IS NULL AND first_type_generation IS NULL AND desired_workflow_id IS NOT NULL)
);
