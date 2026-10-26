--liquibase formatted sql

-- changeset taska:0002-issue-type-generation
-- comment: Preserve legacy binding identity while fencing recreated issue type codes.
ALTER TABLE taska.project_issue_types ADD COLUMN generation uuid;
UPDATE taska.project_issue_types SET generation = md5(project_id::text || ':' || code)::uuid;
ALTER TABLE taska.project_issue_types ALTER COLUMN generation SET NOT NULL;
ALTER TABLE taska.project_issue_types ALTER COLUMN generation SET DEFAULT gen_random_uuid();
