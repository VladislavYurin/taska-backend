SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.tenant_rename_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  administrative boolean NOT NULL,
  result text NOT NULL CHECK (result IN ('SUCCEEDED','NO_CHANGE')),
  old_name varchar(128) NOT NULL,
  new_name varchar(128) NOT NULL,
  tenant_version bigint NOT NULL CHECK (tenant_version > 0),
  operation_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  UNIQUE (tenant_id,operation_id)
);
CREATE INDEX tenant_rename_audit_actor_idx
  ON taska.tenant_rename_audit (tenant_id,actor_id,created_at);
