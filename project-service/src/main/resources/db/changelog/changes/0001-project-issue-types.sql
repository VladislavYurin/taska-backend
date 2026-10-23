--liquibase formatted sql

-- changeset taska:0001-project-issue-types
CREATE TABLE taska.project_issue_types (
    project_id uuid NOT NULL REFERENCES taska.projects(id) ON DELETE CASCADE,
    code varchar(32) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    name varchar(50) NOT NULL CHECK (length(btrim(name)) > 0),
    color varchar(16) NOT NULL CHECK (color IN ('blue','red','green','amber','purple','teal','slate')),
    icon varchar(32) NOT NULL CHECK (icon IN ('check-square','bug','book-open','headset','megaphone','file-text','flag','layers')),
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, code)
);

-- Preserve configured subsets and custom keys. Bad legacy keys fail the migration
-- instead of silently dropping a project's types. Missing/empty settings use defaults.
WITH configured AS (
    SELECT p.id, s.settings,
           CASE WHEN jsonb_typeof(s.settings->'allowedIssueTypes') = 'array' THEN
               CASE WHEN jsonb_array_length(s.settings->'allowedIssueTypes') > 0
                    THEN s.settings->'allowedIssueTypes'
                    ELSE '["TASK","BUG","STORY"]'::jsonb END
               ELSE '["TASK","BUG","STORY"]'::jsonb END AS types
    FROM taska.projects p LEFT JOIN taska.project_settings s ON s.project_id = p.id
), expanded AS (
    SELECT DISTINCT ON (id, value) id, settings, value AS code, ordinality
    FROM configured CROSS JOIN LATERAL jsonb_array_elements_text(types) WITH ORDINALITY
    ORDER BY id, value, ordinality
)
INSERT INTO taska.project_issue_types(project_id, code, name, color, icon, sort_order)
SELECT id, code,
       CASE code WHEN 'TASK' THEN 'Task' WHEN 'BUG' THEN 'Bug' WHEN 'STORY' THEN 'Story' ELSE code END,
       CASE code WHEN 'BUG' THEN 'red' WHEN 'STORY' THEN 'green' ELSE 'blue' END,
       CASE code WHEN 'TASK' THEN 'check-square' WHEN 'BUG' THEN 'bug' WHEN 'STORY' THEN 'book-open' ELSE 'file-text' END,
       CASE WHEN code = settings->>'defaultIssueType' THEN 0 ELSE ordinality::integer * 10 END
FROM expanded;

CREATE INDEX project_issue_types_order_idx ON taska.project_issue_types(project_id, sort_order, code);
-- Legacy JSON keys remain a compatibility mirror during the rolling upgrade.
-- The registry is authoritative for all new reads and writes.
