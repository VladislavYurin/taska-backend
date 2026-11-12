SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;

ALTER TABLE taska.membership_audit
  DROP CONSTRAINT membership_audit_action_check;
ALTER TABLE taska.membership_audit
  ADD CONSTRAINT membership_audit_action_check
  CHECK (action IN ('setTenantMemberRole','setTenantMemberState','removeTenantMember','leaveTenant'));
