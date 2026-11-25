SET LOCAL ROLE taska_workflow_migrator;
CREATE TABLE taska.project_cleanup_receipts(
 tenant_id uuid NOT NULL,project_id uuid NOT NULL,operation_id uuid NOT NULL,completed boolean NOT NULL DEFAULT false,
 PRIMARY KEY(tenant_id,project_id),UNIQUE(tenant_id,operation_id)
);
CREATE TABLE taska.workflow_resource_identities(
 tenant_id uuid NOT NULL,owner_project_id uuid NOT NULL,id uuid NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,owner_project_id,id)
);
INSERT INTO taska.workflow_resource_identities SELECT tenant_id,owner_project_id,id FROM taska.workflows;
CREATE FUNCTION taska.remember_workflow_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN INSERT INTO taska.workflow_resource_identities VALUES(NEW.tenant_id,NEW.owner_project_id,NEW.id) ON CONFLICT DO NOTHING; RETURN NEW; END $$;
CREATE TRIGGER remember_workflow_identity BEFORE INSERT ON taska.workflows FOR EACH ROW EXECUTE FUNCTION taska.remember_workflow_identity();
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname,pg_get_constraintdef(oid) AS def FROM pg_constraint
 WHERE contype='f' AND confrelid='taska.workflows'::regclass AND conrelid='taska.workflow_template_instances'::regclass
 LOOP
  EXECUTE format('ALTER TABLE taska.workflow_template_instances DROP CONSTRAINT %I',c.conname);
  EXECUTE format('ALTER TABLE taska.workflow_template_instances ADD CONSTRAINT %I %s',c.conname,replace(c.def,'REFERENCES taska.workflows','REFERENCES taska.workflow_resource_identities'));
 END LOOP;
END $$;
CREATE FUNCTION taska.guard_workflow_project_cleanup() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE p uuid;
BEGIN
 IF TG_TABLE_NAME='workflows' THEN p=NEW.owner_project_id;
 ELSIF TG_TABLE_NAME IN ('workflow_bindings','workflow_template_instances') THEN p=NEW.project_id;
 ELSIF TG_TABLE_NAME='validator_rules' THEN
 SELECT w.owner_project_id INTO p FROM taska.transitions t JOIN taska.workflows w ON w.tenant_id=t.tenant_id AND w.id=t.workflow_id WHERE t.tenant_id=NEW.tenant_id AND t.id=NEW.transition_id;
 ELSE SELECT owner_project_id INTO p FROM taska.workflows WHERE tenant_id=NEW.tenant_id AND id=NEW.workflow_id; END IF;
 IF p IS NULL THEN RETURN NEW; END IF;
 IF EXISTS(SELECT 1 FROM taska.project_cleanup_receipts WHERE tenant_id=NEW.tenant_id AND project_id=p) THEN RAISE EXCEPTION 'PROJECT_DELETING' USING ERRCODE='23514'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text||'/project-cleanup/'||p::text,0) % 256);
 IF EXISTS(SELECT 1 FROM taska.project_cleanup_receipts c WHERE c.tenant_id=NEW.tenant_id AND c.project_id=p)
 THEN RAISE EXCEPTION 'PROJECT_DELETING' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workflow_project_cleanup BEFORE INSERT OR UPDATE ON taska.workflows FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_project_cleanup();
CREATE TRIGGER binding_project_cleanup BEFORE INSERT OR UPDATE ON taska.workflow_bindings FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_project_cleanup();
CREATE TRIGGER instance_project_cleanup BEFORE INSERT ON taska.workflow_template_instances FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_project_cleanup();
CREATE TRIGGER status_project_cleanup BEFORE INSERT OR UPDATE ON taska.statuses FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_project_cleanup();
CREATE TRIGGER transition_project_cleanup BEFORE INSERT OR UPDATE ON taska.transitions FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_project_cleanup();
CREATE TRIGGER validator_project_cleanup BEFORE INSERT OR UPDATE ON taska.validator_rules FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_project_cleanup();

CREATE FUNCTION taska.guard_workflow_cleanup_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Project cleanup receipt is retained'; END IF;
 IF (NEW.tenant_id,NEW.project_id,NEW.operation_id) IS DISTINCT FROM (OLD.tenant_id,OLD.project_id,OLD.operation_id)
 OR (OLD.completed AND NOT NEW.completed) THEN RAISE EXCEPTION 'Project cleanup identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workflow_cleanup_receipt BEFORE UPDATE OR DELETE ON taska.project_cleanup_receipts FOR EACH ROW EXECUTE FUNCTION taska.guard_workflow_cleanup_receipt();
