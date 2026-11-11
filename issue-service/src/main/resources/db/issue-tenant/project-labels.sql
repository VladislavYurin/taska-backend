SET LOCAL ROLE taska_issue_migrator;
-- Narrow context read foundation; labels CRUD/issue attachment is supplied by TAS-343.
CREATE TABLE taska.project_labels(
 tenant_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),project_id uuid NOT NULL,
 name text NOT NULL CHECK(length(btrim(name))>0),color text NOT NULL,created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),deleted_at timestamptz,
 PRIMARY KEY(tenant_id,id),CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE UNIQUE INDEX project_labels_active_name ON taska.project_labels(tenant_id,project_id,lower(btrim(name))) WHERE deleted_at IS NULL;
