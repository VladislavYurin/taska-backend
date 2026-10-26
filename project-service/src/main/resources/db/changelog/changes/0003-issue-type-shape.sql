--liquibase formatted sql

-- changeset taska:0003-issue-type-shape
-- comment: TAS-432 — a type gets a shape and its icon becomes optional, untouched built-ins return to the plain look.
ALTER TABLE taska.project_issue_types ADD COLUMN shape varchar(16) NOT NULL DEFAULT 'square'
    CONSTRAINT project_issue_types_shape_check CHECK (shape IN ('square','circle','diamond'));

-- The icon CHECK from 0001 stays as it is: a CHECK passes on NULL, so only NOT NULL has to go.
ALTER TABLE taska.project_issue_types ALTER COLUMN icon DROP NOT NULL;

-- Built-ins still wearing their seed icon lose it. A built-in whose icon an admin changed keeps it, as does every other row.
UPDATE taska.project_issue_types
SET icon = NULL
WHERE (code = 'TASK' AND icon = 'check-square')
   OR (code = 'BUG' AND icon = 'bug')
   OR (code = 'STORY' AND icon = 'book-open');

-- A bug is round whatever its icon. Every other type keeps the default square.
UPDATE taska.project_issue_types SET shape = 'circle' WHERE code = 'BUG';

-- Undo puts icons back exactly as client.ProjectIssueTypes.legacyIcon gives them to pre-TAS-432 readers.
--rollback UPDATE taska.project_issue_types SET icon = CASE code WHEN 'BUG' THEN 'bug' WHEN 'STORY' THEN 'book-open' ELSE 'check-square' END WHERE icon IS NULL;
--rollback ALTER TABLE taska.project_issue_types ALTER COLUMN icon SET NOT NULL;
--rollback ALTER TABLE taska.project_issue_types DROP COLUMN shape;
