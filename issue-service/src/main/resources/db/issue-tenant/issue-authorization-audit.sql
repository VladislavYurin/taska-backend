SET LOCAL ROLE taska_issue_migrator;
CREATE TABLE taska.issue_authorization_audit (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id uuid NOT NULL,
  project_id uuid NOT NULL,
  issue_id uuid,
  action text NOT NULL,
  request_id text NOT NULL,
  bypassed jsonb NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CHECK (jsonb_typeof(bypassed)='array')
);
CREATE INDEX issue_authorization_audit_tenant_idx ON taska.issue_authorization_audit(tenant_id,occurred_at);
