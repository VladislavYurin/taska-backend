--liquibase formatted sql

-- changeset taska:0005-issue-type-generation
-- comment: A binding belongs to one lifecycle of a project issue type, not a reusable code.
ALTER TABLE taska.workflow_bindings ADD COLUMN issue_type_generation uuid;
-- NULL rows are legacy bindings, matched only to the deterministic initial registry generation.
