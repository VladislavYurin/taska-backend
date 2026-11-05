SET LOCAL ROLE taska_project_migrator;
ALTER TABLE taska.projects ADD COLUMN admission_state text NOT NULL DEFAULT 'OPEN' CHECK(admission_state IN ('PROVISIONING','OPEN'));
CREATE TABLE taska.resource_operation_receipts(
 tenant_id uuid NOT NULL,
 operation_id uuid NOT NULL,
 resource_kind text NOT NULL CHECK(resource_kind='PROJECT'),
 resource_id uuid NOT NULL,
 reservation_id uuid NOT NULL,
 reservation_fence bigint NOT NULL CHECK(reservation_fence>0),
 reservation_expiry timestamptz NOT NULL,
 state text NOT NULL CHECK(state IN ('COMMITTED','CANCELLED','DELETED')),
 amount bigint NOT NULL CHECK(amount=1),
 request_hash text,
 committed_at timestamptz,
 opened_at timestamptz,
 PRIMARY KEY(tenant_id,operation_id,resource_kind),
 UNIQUE(tenant_id,resource_kind,resource_id),
 UNIQUE(tenant_id,resource_kind,resource_id,reservation_id),
 CHECK(request_hash IS NULL OR request_hash ~ '^[a-f0-9]{64}$'),
 CHECK(state<>'COMMITTED' OR (request_hash IS NOT NULL AND committed_at IS NOT NULL))
);
CREATE FUNCTION taska.guard_resource_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Resource receipt must survive resource deletion'; END IF;
 IF (NEW.tenant_id,NEW.operation_id,NEW.resource_kind,NEW.resource_id,NEW.reservation_id,NEW.reservation_fence,NEW.reservation_expiry,NEW.amount)
   IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id,OLD.resource_kind,OLD.resource_id,OLD.reservation_id,OLD.reservation_fence,OLD.reservation_expiry,OLD.amount)
   OR (OLD.request_hash IS NOT NULL AND NEW.request_hash IS DISTINCT FROM OLD.request_hash)
   OR (OLD.state IN ('CANCELLED','DELETED') AND NEW IS DISTINCT FROM OLD)
   OR (OLD.opened_at IS NOT NULL AND (NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.state='CANCELLED'))
 THEN RAISE EXCEPTION 'Resource receipt identity/state is terminal'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER guard_resource_receipt BEFORE UPDATE OR DELETE ON taska.resource_operation_receipts FOR EACH ROW EXECUTE FUNCTION taska.guard_resource_receipt();

-- Cancellation of a never-opened provisional aggregate is distinct from product hard deletion.
CREATE OR REPLACE FUNCTION taska.project_no_delete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF OLD.admission_state='PROVISIONING' AND EXISTS(
   SELECT 1 FROM taska.resource_operation_receipts r WHERE r.tenant_id=OLD.tenant_id AND r.resource_id=OLD.id
     AND r.resource_kind='PROJECT' AND r.state='CANCELLED' AND r.opened_at IS NULL)
 THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Project deletion is not enabled' USING ERRCODE='23514';
END $body$;
CREATE FUNCTION taska.project_admission_monotonic() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF (OLD.admission_state='OPEN' AND NEW.admission_state<>'OPEN') OR NEW.tenant_id<>OLD.tenant_id OR NEW.id<>OLD.id
 THEN RAISE EXCEPTION 'Project admission identity/state is monotonic' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER project_admission_monotonic BEFORE UPDATE ON taska.projects FOR EACH ROW EXECUTE FUNCTION taska.project_admission_monotonic();
