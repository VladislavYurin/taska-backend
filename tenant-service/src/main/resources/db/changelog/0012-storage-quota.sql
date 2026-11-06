SET LOCAL ROLE taska_tenant_migrator;
ALTER TABLE taska.quota_counters ADD COLUMN storage_quota_version bigint NOT NULL DEFAULT 0 CHECK(storage_quota_version>=0);
ALTER TABLE taska.quota_reservations ADD COLUMN storage_project_id uuid, ADD COLUMN storage_issue_id uuid,
 ADD COLUMN storage_policy_version bigint, ADD COLUMN storage_max_attachment_bytes bigint,
 ADD COLUMN storage_actor_id uuid, ADD COLUMN storage_credential_kind text, ADD COLUMN storage_grant_id uuid,
 ADD COLUMN storage_bypassed_limit boolean NOT NULL DEFAULT false, ADD COLUMN storage_actual_bytes bigint,
 ADD COLUMN storage_cancel_requested_at timestamptz, ADD COLUMN storage_activated_at timestamptz;
ALTER TABLE taska.quota_reservations ADD CHECK(resource<>'STORAGE_BYTES' OR
 (storage_actor_id IS NOT NULL AND storage_project_id IS NOT NULL AND storage_issue_id IS NOT NULL AND storage_policy_version>0
 AND storage_max_attachment_bytes>0 AND amount<=storage_max_attachment_bytes AND storage_credential_kind IS NOT NULL
 AND (storage_actual_bytes IS NULL OR storage_actual_bytes BETWEEN 1 AND amount)
 AND (state<>'COMMITTED' OR storage_actual_bytes IS NOT NULL)));
CREATE UNIQUE INDEX storage_quota_resource_once ON taska.quota_reservations(tenant_id,resource_id) WHERE resource='STORAGE_BYTES';
CREATE INDEX storage_quota_recovery ON taska.quota_reservations(tenant_id,created_at,id) WHERE resource='STORAGE_BYTES' AND (state='ACTIVE' OR (state='COMMITTED' AND storage_activated_at IS NULL));
CREATE FUNCTION taska.guard_storage_quota_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF OLD.resource<>'STORAGE_BYTES' THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Storage quota receipt must survive deletion'; END IF;
 IF (NEW.tenant_id,NEW.id,NEW.resource,NEW.scope_id,NEW.operation_id,NEW.resource_owner,NEW.resource_id,NEW.request_hash,NEW.amount,NEW.fence,NEW.entitlement_version,NEW.expires_at,NEW.created_at,NEW.storage_project_id,NEW.storage_issue_id,NEW.storage_policy_version,NEW.storage_max_attachment_bytes,NEW.storage_actor_id,NEW.storage_credential_kind,NEW.storage_grant_id,NEW.storage_bypassed_limit)
 IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.resource,OLD.scope_id,OLD.operation_id,OLD.resource_owner,OLD.resource_id,OLD.request_hash,OLD.amount,OLD.fence,OLD.entitlement_version,OLD.expires_at,OLD.created_at,OLD.storage_project_id,OLD.storage_issue_id,OLD.storage_policy_version,OLD.storage_max_attachment_bytes,OLD.storage_actor_id,OLD.storage_credential_kind,OLD.storage_grant_id,OLD.storage_bypassed_limit)
 OR (OLD.state<>'ACTIVE' AND NEW.state<>OLD.state) OR NEW.version<OLD.version
 OR (OLD.storage_actual_bytes IS NOT NULL AND NEW.storage_actual_bytes IS DISTINCT FROM OLD.storage_actual_bytes)
 OR (OLD.storage_cancel_requested_at IS NOT NULL AND NEW.storage_cancel_requested_at IS DISTINCT FROM OLD.storage_cancel_requested_at)
 OR (NEW.state='COMMITTED' AND NEW.storage_cancel_requested_at IS NOT NULL)
 THEN RAISE EXCEPTION 'Storage quota identity/measurement is immutable'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER guard_storage_quota_receipt BEFORE UPDATE OR DELETE ON taska.quota_reservations FOR EACH ROW EXECUTE FUNCTION taska.guard_storage_quota_receipt();
