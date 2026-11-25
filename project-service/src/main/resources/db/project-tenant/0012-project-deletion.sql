SET LOCAL ROLE taska_project_migrator;
CREATE TABLE taska.project_deletion_operations(
 tenant_id uuid NOT NULL,id uuid NOT NULL,project_id uuid NOT NULL,actor_id uuid NOT NULL,
 authority text NOT NULL,credential_kind text NOT NULL,actor_generation text NOT NULL,membership_generation text NOT NULL,
 policy_version text NOT NULL,request_id uuid NOT NULL,request_key uuid NOT NULL,expected_version integer NOT NULL,
 confirmed_key text NOT NULL,state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RESOURCES_DELETED','DELETED','SUCCEEDED')),
 issue_done boolean NOT NULL DEFAULT false,workflow_done boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 last_code varchar(64),manual_review boolean NOT NULL DEFAULT false,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,project_id),UNIQUE(tenant_id,actor_id,authority,request_key)
);
CREATE FUNCTION taska.guard_project_deletion_operation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Подтверждение удаления сохраняется'; END IF;
 IF (NEW.tenant_id,NEW.id,NEW.project_id,NEW.actor_id,NEW.authority,NEW.credential_kind,NEW.actor_generation,NEW.membership_generation,NEW.policy_version,NEW.request_id,NEW.request_key,NEW.expected_version,NEW.confirmed_key,NEW.created_at)
 IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.project_id,OLD.actor_id,OLD.authority,OLD.credential_kind,OLD.actor_generation,OLD.membership_generation,OLD.policy_version,OLD.request_id,OLD.request_key,OLD.expected_version,OLD.confirmed_key,OLD.created_at)
 OR (OLD.issue_done AND NOT NEW.issue_done) OR (OLD.workflow_done AND NOT NEW.workflow_done)
 OR array_position(ARRAY['PENDING','RESOURCES_DELETED','DELETED','SUCCEEDED'],NEW.state)<array_position(ARRAY['PENDING','RESOURCES_DELETED','DELETED','SUCCEEDED'],OLD.state)
 THEN RAISE EXCEPTION 'Подтверждение удаления неизменно'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_project_deletion_operation BEFORE UPDATE OR DELETE ON taska.project_deletion_operations FOR EACH ROW EXECUTE FUNCTION taska.guard_project_deletion_operation();
CREATE FUNCTION taska.guard_project_deletion_write() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE t uuid; p uuid;
BEGIN
 t=NEW.tenant_id;
 IF TG_TABLE_NAME='projects' THEN p=NEW.id; ELSE p=NEW.project_id; END IF;
 IF EXISTS(SELECT 1 FROM taska.project_deletion_operations WHERE tenant_id=t AND project_id=p) THEN RAISE EXCEPTION 'PROJECT_DELETING' USING ERRCODE='23514'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(t::text||'/project-delete/'||p::text,0) % 256);
 IF EXISTS(SELECT 1 FROM taska.project_deletion_operations d WHERE d.tenant_id=t AND d.project_id=p)
 THEN RAISE EXCEPTION 'PROJECT_DELETING' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER project_deleting_write BEFORE INSERT OR UPDATE ON taska.projects FOR EACH ROW EXECUTE FUNCTION taska.guard_project_deletion_write();
CREATE TRIGGER project_members_deleting_write BEFORE INSERT OR UPDATE ON taska.project_members FOR EACH ROW EXECUTE FUNCTION taska.guard_project_deletion_write();
CREATE TRIGGER project_settings_deleting_write BEFORE INSERT OR UPDATE ON taska.project_settings FOR EACH ROW EXECUTE FUNCTION taska.guard_project_deletion_write();
CREATE TRIGGER project_types_deleting_write BEFORE INSERT OR UPDATE ON taska.project_issue_types FOR EACH ROW EXECUTE FUNCTION taska.guard_project_deletion_write();
CREATE OR REPLACE FUNCTION taska.project_no_delete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.admission_state='PROVISIONING' AND EXISTS(SELECT 1 FROM taska.resource_operation_receipts r WHERE r.tenant_id=OLD.tenant_id AND r.resource_id=OLD.id AND r.resource_kind='PROJECT' AND r.state='CANCELLED' AND r.opened_at IS NULL) THEN RETURN OLD; END IF;
 IF EXISTS(SELECT 1 FROM taska.project_deletion_operations d WHERE d.tenant_id=OLD.tenant_id AND d.project_id=OLD.id AND d.state='RESOURCES_DELETED' AND d.issue_done AND d.workflow_done) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Подтверждённая очистка проекта обязательна' USING ERRCODE='23514';
END $$;

-- Идентичность переживает business row: журналы и квотные receipts не теряют ссылку.
CREATE TABLE taska.project_resource_identities(tenant_id uuid NOT NULL,id uuid NOT NULL,PRIMARY KEY(tenant_id,id));
INSERT INTO taska.project_resource_identities SELECT tenant_id,id FROM taska.projects;
CREATE FUNCTION taska.remember_project_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN INSERT INTO taska.project_resource_identities VALUES(NEW.tenant_id,NEW.id) ON CONFLICT DO NOTHING; RETURN NEW; END $$;
CREATE TRIGGER remember_project_identity BEFORE INSERT ON taska.projects FOR EACH ROW EXECUTE FUNCTION taska.remember_project_identity();
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname,conrelid::regclass AS tbl FROM pg_constraint WHERE contype='f' AND confrelid='taska.projects'::regclass
   AND conrelid IN ('taska.project_type_operations'::regclass,'taska.issue_type_creations'::regclass,'taska.issue_type_deletions'::regclass,'taska.project_lifecycle_commands'::regclass)
 LOOP
   EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',c.tbl,c.conname);
   EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY(tenant_id,project_id) REFERENCES taska.project_resource_identities(tenant_id,id)',c.tbl,c.conname);
 END LOOP;
END $$;
