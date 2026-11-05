-- Consolidated empty-schema bootstrap for the tenant profile. Legacy changesets stay unchanged.
SET LOCAL ROLE taska_project_migrator;
DO $fresh$ BEGIN
  IF EXISTS (SELECT FROM pg_class WHERE relnamespace='taska'::regnamespace AND relkind IN ('r','p')) THEN
    RAISE EXCEPTION 'Tenant storage requires a fresh service database; existing tables are never reassigned';
  END IF;
END $fresh$;




CREATE TABLE IF NOT EXISTS taska.projects
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    project_key text        NOT NULL UNIQUE,
    name        text        NOT NULL,
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz NULL
);

CREATE INDEX IF NOT EXISTS projects_created_by_idx
    ON taska.projects (created_by);

CREATE TABLE IF NOT EXISTS taska.project_members
(
    project_id uuid        NOT NULL REFERENCES taska.projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,
    added_at   timestamptz NOT NULL DEFAULT now(),
    added_by   uuid        NOT NULL,

    PRIMARY KEY (project_id, user_id),

    CONSTRAINT ck_project_members_role CHECK
        (role IN ('ADMIN', 'MEMBER', 'VIEWER'))
);

CREATE INDEX IF NOT EXISTS project_members_user_idx
    ON taska.project_members (user_id);

CREATE INDEX IF NOT EXISTS project_members_project_role_idx
    ON taska.project_members (project_id, role);

CREATE TABLE IF NOT EXISTS taska.project_settings
(
    project_id uuid PRIMARY KEY REFERENCES taska.projects (id) ON DELETE CASCADE,
    settings   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid        NOT NULL
);

CREATE TABLE IF NOT EXISTS taska.outbox_events
(
    id                    uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    aggregate_type        text        NOT NULL,
    aggregate_id          uuid        NOT NULL,
    event_type            text        NOT NULL,
    payload               jsonb       NOT NULL,
    status                text        NOT NULL DEFAULT 'NEW',
    created_at            timestamptz NOT NULL DEFAULT now(),
    published_at          timestamptz NULL,
    attempts              integer     NOT NULL DEFAULT 0,
    last_error_message    text        NULL,
    processing_started_at timestamptz NULL,
    request_id            text        NULL,

    CONSTRAINT outbox_events_attempts_chk CHECK (attempts >= 0),

    CONSTRAINT outbox_events_status_chk CHECK
        (status IN ('NEW', 'PROCESSING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS outbox_events_unpublished_idx
    ON taska.outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON taska.outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS outbox_events_status_created_idx
    ON taska.outbox_events (status, created_at)
    WHERE status IN ('NEW', 'PROCESSING');

CREATE INDEX IF NOT EXISTS outbox_events_processing_idx
    ON taska.outbox_events (status, processing_started_at)
    WHERE status = 'PROCESSING'
        AND processing_started_at IS NOT NULL;



ALTER TABLE taska.projects
    ADD COLUMN description text NULL,
    ADD COLUMN color varchar(7) NULL;


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



ALTER TABLE taska.projects
    ADD COLUMN version integer NOT NULL DEFAULT 0;


ALTER TABLE taska.project_issue_types ADD COLUMN generation uuid;
UPDATE taska.project_issue_types SET generation = md5(project_id::text || ':' || code)::uuid;
ALTER TABLE taska.project_issue_types ALTER COLUMN generation SET NOT NULL;
ALTER TABLE taska.project_issue_types ALTER COLUMN generation SET DEFAULT gen_random_uuid();


ALTER TABLE taska.project_issue_types ADD COLUMN shape varchar(16) NOT NULL DEFAULT 'square'
    CONSTRAINT project_issue_types_shape_check CHECK (shape IN ('square','circle','diamond'));

ALTER TABLE taska.project_issue_types ALTER COLUMN icon DROP NOT NULL;

UPDATE taska.project_issue_types
SET icon = NULL
WHERE (code = 'TASK' AND icon = 'check-square')
   OR (code = 'BUG' AND icon = 'bug')
   OR (code = 'STORY' AND icon = 'book-open');

UPDATE taska.project_issue_types SET shape = 'circle' WHERE code = 'BUG';


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

ALTER TABLE taska.issue_type_creations ALTER COLUMN first_type_code DROP NOT NULL;
ALTER TABLE taska.issue_type_creations ALTER COLUMN first_type_generation DROP NOT NULL;
ALTER TABLE taska.issue_type_creations ADD CONSTRAINT creation_default_snapshot_required CHECK (
    (first_type_code IS NOT NULL AND first_type_generation IS NOT NULL)
    OR (first_type_code IS NULL AND first_type_generation IS NULL AND desired_workflow_id IS NOT NULL)
);
