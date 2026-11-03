SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.membership_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  target_user_id uuid NOT NULL,
  action text NOT NULL,
  result text NOT NULL,
  old_role text NOT NULL,
  new_role text NOT NULL,
  old_status text NOT NULL,
  new_status text NOT NULL,
  membership_version bigint NOT NULL,
  operation_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  UNIQUE (tenant_id,operation_id),
  CHECK (action IN ('setTenantMemberRole','leaveTenant')),
  CHECK (result IN ('SUCCEEDED','NO_CHANGE')),
  CHECK (old_role IN ('TENANT_ADMIN','MEMBER')),
  CHECK (new_role IN ('TENANT_ADMIN','MEMBER')),
  CHECK (old_status IN ('ACTIVE','SUSPENDED','REMOVED')),
  CHECK (new_status IN ('ACTIVE','SUSPENDED','REMOVED')),
  CHECK (membership_version>0)
);
CREATE INDEX membership_audit_actor_idx ON taska.membership_audit (tenant_id,actor_id,created_at);
