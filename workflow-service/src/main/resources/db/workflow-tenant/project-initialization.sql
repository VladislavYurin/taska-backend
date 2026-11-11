SET LOCAL ROLE taska_workflow_migrator;
CREATE TABLE taska.project_initializations(
 tenant_id uuid NOT NULL,operation_id uuid NOT NULL,project_id uuid NOT NULL,
 reservation_id uuid NOT NULL,reservation_fence bigint NOT NULL CHECK(reservation_fence>0),reservation_expiry timestamptz NOT NULL,
 request_hash text,workflow_id uuid,state text NOT NULL CHECK(state IN ('PREPARED','CANCELLED')),
 PRIMARY KEY(tenant_id,operation_id),UNIQUE(tenant_id,project_id),
 CHECK(state<>'PREPARED' OR (request_hash IS NOT NULL AND request_hash ~ '^[a-f0-9]{64}$' AND workflow_id IS NOT NULL))
);
CREATE FUNCTION taska.project_initialization_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Project initialization tombstone must survive';END IF;
 IF (NEW.tenant_id,NEW.operation_id,NEW.project_id,NEW.reservation_id,NEW.reservation_fence,NEW.reservation_expiry,NEW.request_hash,NEW.workflow_id)
  IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id,OLD.project_id,OLD.reservation_id,OLD.reservation_fence,OLD.reservation_expiry,OLD.request_hash,OLD.workflow_id)
  OR (OLD.state='CANCELLED' AND NEW IS DISTINCT FROM OLD) THEN RAISE EXCEPTION 'Project initialization identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER project_initialization_identity BEFORE UPDATE OR DELETE ON taska.project_initializations FOR EACH ROW EXECUTE FUNCTION taska.project_initialization_identity();
CREATE FUNCTION taska.project_instance_cancel() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' AND EXISTS(SELECT FROM taska.project_initializations p WHERE p.tenant_id=OLD.tenant_id AND p.project_id=OLD.project_id AND p.workflow_id=OLD.workflow_id AND p.state='CANCELLED') THEN RETURN OLD;END IF;
 RAISE EXCEPTION 'Immutable workflow instantiation receipt' USING ERRCODE='23514';
END $$;
DROP TRIGGER immutable_instance ON taska.workflow_template_instances;
CREATE TRIGGER immutable_instance BEFORE UPDATE OR DELETE ON taska.workflow_template_instances FOR EACH ROW EXECUTE FUNCTION taska.project_instance_cancel();
