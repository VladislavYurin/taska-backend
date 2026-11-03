SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE INDEX tenant_memberships_page_idx
  ON taska.tenant_memberships (tenant_id,joined_at,user_id)
  WHERE status IN ('ACTIVE','SUSPENDED');
