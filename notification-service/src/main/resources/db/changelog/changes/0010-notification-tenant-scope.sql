--liquibase formatted sql
-- changeset taska:0010-notification-tenant-scope
ALTER TABLE taska.notifications ADD COLUMN tenant_id uuid;
CREATE INDEX notifications_tenant_user_created_idx ON taska.notifications(tenant_id,user_id,created_at DESC);
