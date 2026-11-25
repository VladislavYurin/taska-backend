SET LOCAL ROLE taska_project_migrator;
ALTER TABLE taska.project_members ADD COLUMN root_membership_version bigint CHECK(root_membership_version>0);
CREATE TABLE taska.project_creation_operations(
 tenant_id uuid NOT NULL,operation_id uuid NOT NULL,project_id uuid NOT NULL,actor_id uuid NOT NULL,
 actor_membership_generation bigint NOT NULL CHECK(actor_membership_generation>0),
 request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),request jsonb NOT NULL,
 reservation_expiry timestamptz NOT NULL,state text NOT NULL DEFAULT 'CREATING' CHECK(state IN ('CREATING','OPEN','CANCELLING','CANCELLED')),
 initialized_at timestamptz,workflow_id uuid,recovery_checked_at timestamptz NOT NULL DEFAULT now(),created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,operation_id),UNIQUE(tenant_id,project_id)
);
CREATE FUNCTION taska.project_creation_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Project creation receipts must survive'; END IF;
 IF (NEW.tenant_id,NEW.operation_id,NEW.project_id,NEW.actor_id,NEW.actor_membership_generation,NEW.request_hash,NEW.request,NEW.reservation_expiry,NEW.created_at)
   IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id,OLD.project_id,OLD.actor_id,OLD.actor_membership_generation,OLD.request_hash,OLD.request,OLD.reservation_expiry,OLD.created_at)
   OR (OLD.state IN ('OPEN','CANCELLED') AND NEW IS DISTINCT FROM OLD)
   OR (OLD.initialized_at IS NOT NULL AND (NEW.initialized_at IS DISTINCT FROM OLD.initialized_at OR NEW.workflow_id IS DISTINCT FROM OLD.workflow_id))
 THEN RAISE EXCEPTION 'Project creation identity is immutable'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER project_creation_identity BEFORE UPDATE OR DELETE ON taska.project_creation_operations FOR EACH ROW EXECUTE FUNCTION taska.project_creation_identity();
CREATE TABLE taska.project_authorization_audit(
 tenant_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),actor_id uuid NOT NULL,
 action text NOT NULL,project_id uuid,request_id text NOT NULL,policy_version text NOT NULL,
 decision jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,id)
);
