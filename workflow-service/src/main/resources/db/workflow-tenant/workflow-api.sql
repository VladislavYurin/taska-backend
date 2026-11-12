SET LOCAL ROLE taska_workflow_migrator;
SET LOCAL search_path=pg_catalog;

CREATE TABLE taska.workflow_authorization_audit (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  actor_id uuid NOT NULL,
  project_id uuid NOT NULL,
  action text NOT NULL,
  request_id text NOT NULL,
  policy_version text NOT NULL,
  decision jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(id),
  UNIQUE(tenant_id,id)
);
CREATE INDEX workflow_authorization_audit_project_idx
  ON taska.workflow_authorization_audit(tenant_id,project_id,created_at,id);

CREATE TABLE taska.workflow_creation_receipts (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  project_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id text NOT NULL,
  command_hash text NOT NULL CHECK(command_hash ~ '^[a-f0-9]{64}$'),
  result_bytes bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(id),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,project_id,actor_id,request_id)
);

CREATE UNIQUE INDEX statuses_workflow_order_unique
  ON taska.statuses(tenant_id,workflow_id,sort_order);
CREATE UNIQUE INDEX transitions_workflow_order_unique
  ON taska.transitions(tenant_id,workflow_id,sort_order);

SELECT set_config('taska.guard_service','workflow',true),
       set_config('taska.guard_schema','taska',true),
       set_config('taska.guard_table','workflow_authorization_audit',true),
       set_config('taska.guard_column','tenant_id',true),
       set_config('taska.guard_app_access','append_only',true);
