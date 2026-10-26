--liquibase formatted sql

-- changeset taska:0003-issue-type-shape
-- comment: TAS-432 — a type gets a shape and its icon becomes optional, untouched built-ins return to the plain look.
ALTER TABLE taska.project_issue_types ADD COLUMN shape varchar(16) NOT NULL DEFAULT 'square'
    CONSTRAINT project_issue_types_shape_check CHECK (shape IN ('square','circle','diamond'));

-- 0001 declared the icon CHECK inline, so PostgreSQL named it project_issue_types_icon_check.
ALTER TABLE taska.project_issue_types DROP CONSTRAINT project_issue_types_icon_check;
ALTER TABLE taska.project_issue_types ALTER COLUMN icon DROP NOT NULL;
ALTER TABLE taska.project_issue_types ADD CONSTRAINT project_issue_types_icon_check
    CHECK (icon IS NULL OR icon IN ('check-square','bug','book-open','headset','megaphone','file-text','flag','layers'));

-- Built-ins still wearing their seed icon become plain shapes: BUG a circle, TASK and STORY squares.
-- A built-in whose icon an admin changed keeps it, as does every other row, and becomes a square.
UPDATE taska.project_issue_types
SET icon = NULL, shape = CASE code WHEN 'BUG' THEN 'circle' ELSE 'square' END
WHERE (code = 'TASK' AND icon = 'check-square')
   OR (code = 'BUG' AND icon = 'bug')
   OR (code = 'STORY' AND icon = 'book-open');
