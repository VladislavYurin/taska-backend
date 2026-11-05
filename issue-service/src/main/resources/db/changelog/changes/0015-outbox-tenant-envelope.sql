--liquibase formatted sql
-- changeset taska:0015-outbox-tenant-envelope
-- Nullable during the staged migration: legacy rows have no trustworthy tenant source and are rejected by the publisher.
ALTER TABLE taska.outbox_events ADD COLUMN tenant_id uuid;
CREATE INDEX outbox_events_tenant_status_created_idx
    ON taska.outbox_events(tenant_id, status, created_at)
    WHERE tenant_id IS NOT NULL AND status IN ('NEW', 'PROCESSING');
