SET LOCAL ROLE taska_issue_migrator;
ALTER TABLE taska.attachment_object_manifest ADD CONSTRAINT manifest_delete_identity UNIQUE(tenant_id,id,operation_id);
CREATE TABLE taska.attachment_deletion_jobs(
 tenant_id uuid NOT NULL,attachment_id uuid NOT NULL,operation_id uuid NOT NULL,initiator_id uuid NOT NULL,
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','COMPLETED')),
 created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 lease_epoch bigint NOT NULL DEFAULT 0 CHECK(lease_epoch>=0),lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),attempts bigint NOT NULL DEFAULT 0 CHECK(attempts>=0),last_code varchar(64),
 PRIMARY KEY(tenant_id,attachment_id),UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,attachment_id,operation_id) REFERENCES taska.attachment_object_manifest(tenant_id,id,operation_id),
 CHECK((state='COMPLETED')=(completed_at IS NOT NULL))
);
CREATE INDEX attachment_delete_due ON taska.attachment_deletion_jobs(tenant_id,state,next_attempt_at,attachment_id);
CREATE FUNCTION taska.guard_attachment_deletion() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Attachment delete intent is retained'; END IF;
 IF ROW(NEW.tenant_id,NEW.attachment_id,NEW.operation_id,NEW.initiator_id,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.tenant_id,OLD.attachment_id,OLD.operation_id,OLD.initiator_id,OLD.created_at)
 OR NEW.lease_epoch<OLD.lease_epoch OR NEW.attempts<OLD.attempts
 OR (OLD.state='COMPLETED' AND NEW IS DISTINCT FROM OLD)
 THEN RAISE EXCEPTION 'Immutable attachment deletion'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER immutable_attachment_deletion BEFORE UPDATE OR DELETE ON taska.attachment_deletion_jobs FOR EACH ROW EXECUTE FUNCTION taska.guard_attachment_deletion();
