SET LOCAL ROLE taska_tenant_migrator;
ALTER TABLE taska.quota_counters ADD COLUMN project_quota_version bigint NOT NULL DEFAULT 0 CHECK(project_quota_version>=0);
CREATE UNIQUE INDEX quota_project_resource_once ON taska.quota_reservations(tenant_id,resource_id) WHERE resource='PROJECT';
ALTER TABLE taska.quota_reservations ADD COLUMN project_policy_version bigint CHECK(project_policy_version>0);

ALTER TABLE taska.quota_reservations ADD COLUMN project_actor_id uuid,
 ADD COLUMN project_credential_kind text, ADD COLUMN project_grant_id uuid,
 ADD COLUMN project_bypassed_limit boolean NOT NULL DEFAULT false;
ALTER TABLE taska.quota_reservations ADD CHECK(resource<>'PROJECT' OR (amount=1 AND project_policy_version IS NOT NULL AND project_credential_kind IS NOT NULL));
ALTER TABLE taska.quota_reservations ADD COLUMN project_cancel_requested_at timestamptz;
CREATE FUNCTION taska.guard_project_quota_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF OLD.resource<>'PROJECT' THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Project quota receipt must be retained'; END IF;
 IF (NEW.tenant_id,NEW.id,NEW.resource,NEW.scope_id,NEW.operation_id,NEW.resource_owner,NEW.resource_id,NEW.request_hash,NEW.amount,NEW.fence,NEW.entitlement_version,NEW.expires_at,NEW.created_at,NEW.project_policy_version,NEW.project_actor_id,NEW.project_credential_kind,NEW.project_grant_id,NEW.project_bypassed_limit)
 IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.resource,OLD.scope_id,OLD.operation_id,OLD.resource_owner,OLD.resource_id,OLD.request_hash,OLD.amount,OLD.fence,OLD.entitlement_version,OLD.expires_at,OLD.created_at,OLD.project_policy_version,OLD.project_actor_id,OLD.project_credential_kind,OLD.project_grant_id,OLD.project_bypassed_limit)
 OR (OLD.state<>'ACTIVE' AND NEW.state<>OLD.state) OR NEW.version<OLD.version
 OR (OLD.project_cancel_requested_at IS NOT NULL AND NEW.project_cancel_requested_at IS DISTINCT FROM OLD.project_cancel_requested_at)
 OR (NEW.state='COMMITTED' AND NEW.project_cancel_requested_at IS NOT NULL)
 THEN RAISE EXCEPTION 'Project quota admission identity is immutable'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER guard_project_quota_receipt BEFORE UPDATE OR DELETE ON taska.quota_reservations FOR EACH ROW EXECUTE FUNCTION taska.guard_project_quota_receipt();
ALTER TABLE taska.quota_reservations ADD COLUMN project_opened_at timestamptz;
CREATE INDEX project_quota_recovery_idx ON taska.quota_reservations(tenant_id,created_at,id) WHERE resource='PROJECT' AND (state='ACTIVE' OR (state='COMMITTED' AND project_opened_at IS NULL));
