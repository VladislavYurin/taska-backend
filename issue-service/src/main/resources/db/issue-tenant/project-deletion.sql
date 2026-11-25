SET LOCAL ROLE taska_issue_migrator;
CREATE TABLE taska.project_cleanup_receipts(
 tenant_id uuid NOT NULL,project_id uuid NOT NULL,operation_id uuid NOT NULL,initiator_id uuid NOT NULL,completed boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,project_id),UNIQUE(tenant_id,operation_id)
);
ALTER TABLE taska.project_cleanup_receipts ADD UNIQUE(tenant_id,project_id,operation_id);
ALTER TABLE taska.attachment_uploads ADD UNIQUE(tenant_id,project_id,operation_id);
CREATE TABLE taska.project_cleanup_uploads(
 tenant_id uuid NOT NULL,project_id uuid NOT NULL,operation_id uuid NOT NULL,upload_operation_id uuid NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,project_id,upload_operation_id),
 FOREIGN KEY(tenant_id,project_id,operation_id) REFERENCES taska.project_cleanup_receipts(tenant_id,project_id,operation_id),
 FOREIGN KEY(tenant_id,project_id,upload_operation_id) REFERENCES taska.attachment_uploads(tenant_id,project_id,operation_id)
);
CREATE TABLE taska.issue_resource_identities(
 tenant_id uuid NOT NULL,project_id uuid NOT NULL,id uuid NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id) REFERENCES taska.project_counters(tenant_id,project_id)
);
INSERT INTO taska.issue_resource_identities SELECT tenant_id,project_id,id FROM taska.issues;
CREATE FUNCTION taska.remember_issue_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN INSERT INTO taska.issue_resource_identities VALUES(NEW.tenant_id,NEW.project_id,NEW.id) ON CONFLICT DO NOTHING; RETURN NEW; END $$;
CREATE TRIGGER remember_issue_identity BEFORE INSERT ON taska.issues FOR EACH ROW EXECUTE FUNCTION taska.remember_issue_identity();
-- Сохраняемые receipts продолжают ссылаться на identity, а не на удаляемое содержимое задачи.
DO $$ DECLARE c record; cols text; BEGIN
 FOR c IN SELECT conname,conrelid::regclass AS tbl,pg_get_constraintdef(oid) AS def FROM pg_constraint
 WHERE contype='f' AND confrelid='taska.issues'::regclass AND conrelid IN
 ('taska.idempotency_keys'::regclass,'taska.outbox_events'::regclass,'taska.attachment_uploads'::regclass,'taska.attachment_object_manifest'::regclass)
 LOOP
  EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',c.tbl,c.conname);
  EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s',c.tbl,c.conname,replace(c.def,'REFERENCES taska.issues','REFERENCES taska.issue_resource_identities'));
 END LOOP;
END $$;
CREATE FUNCTION taska.guard_issue_project_cleanup() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM taska.project_cleanup_receipts WHERE tenant_id=NEW.tenant_id AND project_id=NEW.project_id) THEN RAISE EXCEPTION 'PROJECT_DELETING' USING ERRCODE='23514'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text||'/project-cleanup/'||NEW.project_id::text,0) % 256);
 IF EXISTS(SELECT 1 FROM taska.project_cleanup_receipts c WHERE c.tenant_id=NEW.tenant_id AND c.project_id=NEW.project_id)
 THEN RAISE EXCEPTION 'PROJECT_DELETING' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER issue_project_cleanup BEFORE INSERT OR UPDATE ON taska.issues FOR EACH ROW EXECUTE FUNCTION taska.guard_issue_project_cleanup();
CREATE TRIGGER upload_project_cleanup BEFORE INSERT ON taska.attachment_uploads FOR EACH ROW EXECUTE FUNCTION taska.guard_issue_project_cleanup();
CREATE TRIGGER label_project_cleanup BEFORE INSERT OR UPDATE ON taska.project_labels FOR EACH ROW EXECUTE FUNCTION taska.guard_issue_project_cleanup();
CREATE TRIGGER watcher_project_cleanup BEFORE INSERT OR UPDATE ON taska.issue_watchers FOR EACH ROW EXECUTE FUNCTION taska.guard_issue_project_cleanup();
CREATE TRIGGER issue_label_project_cleanup BEFORE INSERT OR UPDATE ON taska.issue_labels FOR EACH ROW EXECUTE FUNCTION taska.guard_issue_project_cleanup();

-- Расширение append-only metadata: физическая очистка только после retained primary intent,
-- terminal object proof. Retirement выполняется после удаления всего содержимого.
-- UPDATE/TRUNCATE по-прежнему не выдаются приложению.
CREATE FUNCTION taska.guard_attachment_project_purge() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NULLIF(current_setting('app.actor_id',true),'') IS NOT NULL
 OR NOT EXISTS(SELECT 1 FROM taska.project_cleanup_receipts c
   WHERE c.tenant_id=OLD.tenant_id AND c.project_id=OLD.project_id)
 OR NOT EXISTS(SELECT 1 FROM taska.attachment_object_manifest m
   JOIN taska.attachment_deletion_jobs j ON j.tenant_id=m.tenant_id AND j.attachment_id=m.id AND j.operation_id=m.operation_id
   WHERE m.tenant_id=OLD.tenant_id AND m.project_id=OLD.project_id AND m.issue_id=OLD.issue_id AND m.id=OLD.id AND m.state='DELETED' AND j.state='COMPLETED')
 THEN RAISE EXCEPTION 'PROJECT_CLEANUP_PROOF_REQUIRED' USING ERRCODE='23514'; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER attachment_project_purge BEFORE DELETE ON taska.issue_attachments FOR EACH ROW EXECUTE FUNCTION taska.guard_attachment_project_purge();
GRANT DELETE ON taska.issue_attachments TO taska_issue_app;

CREATE FUNCTION taska.guard_issue_cleanup_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Project cleanup receipt is retained'; END IF;
 IF (NEW.tenant_id,NEW.project_id,NEW.operation_id,NEW.initiator_id,NEW.created_at) IS DISTINCT FROM (OLD.tenant_id,OLD.project_id,OLD.operation_id,OLD.initiator_id,OLD.created_at)
 OR (OLD.completed AND NOT NEW.completed) THEN RAISE EXCEPTION 'Project cleanup identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER issue_cleanup_receipt BEFORE UPDATE OR DELETE ON taska.project_cleanup_receipts FOR EACH ROW EXECUTE FUNCTION taska.guard_issue_cleanup_receipt();

ALTER TABLE taska.attachment_uploads ADD COLUMN project_cleanup_complete boolean NOT NULL DEFAULT false;
CREATE INDEX attachment_project_cleanup_remaining ON taska.attachment_uploads(tenant_id,project_id,id) WHERE NOT project_cleanup_complete;
CREATE FUNCTION taska.guard_upload_cleanup_ack() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF (OLD.project_cleanup_complete AND NOT NEW.project_cleanup_complete)
 OR (NEW.project_cleanup_complete AND NOT EXISTS(SELECT 1 FROM taska.project_cleanup_uploads d WHERE d.tenant_id=NEW.tenant_id AND d.project_id=NEW.project_id AND d.upload_operation_id=NEW.operation_id))
 THEN RAISE EXCEPTION 'CLEANUP_ACK_REQUIRED' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER upload_cleanup_ack BEFORE UPDATE ON taska.attachment_uploads FOR EACH ROW EXECUTE FUNCTION taska.guard_upload_cleanup_ack();
