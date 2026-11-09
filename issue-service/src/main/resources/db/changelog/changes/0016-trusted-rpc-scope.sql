--liquibase formatted sql
-- changeset taska:0016-trusted-rpc-scope splitStatements:false
SET LOCAL search_path=pg_catalog;
CREATE OR REPLACE FUNCTION taska.enforce_legacy_rpc_scope() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE scope_id uuid; fk record; predicate text; present boolean; parent_tenant uuid; i integer;
BEGIN
 IF current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true' THEN RETURN NEW; END IF;
 scope_id:=nullif(current_setting('app.tenant_id',true),'')::uuid;
 IF scope_id IS NULL THEN RAISE EXCEPTION 'Verified tenant required' USING ERRCODE='42501'; END IF;
 IF NEW.tenant_id IS NULL THEN NEW.tenant_id:=scope_id; END IF;
 IF NEW.tenant_id<>scope_id OR (TG_OP='UPDATE' AND NEW.tenant_id IS DISTINCT FROM OLD.tenant_id) THEN
  RAISE EXCEPTION 'Immutable tenant scope' USING ERRCODE='42501';
 END IF;
 FOR fk IN SELECT c.conkey,c.confkey,c.confrelid,n.nspname,p.relname
  FROM pg_constraint c JOIN pg_class p ON p.oid=c.confrelid JOIN pg_namespace n ON n.oid=p.relnamespace
  WHERE c.conrelid=TG_RELID AND c.contype='f' AND n.nspname='taska'
    AND EXISTS(SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.confrelid AND a.attname='tenant_id' AND NOT a.attisdropped)
 LOOP
  predicate:='';
  FOR i IN 1..array_length(fk.conkey,1) LOOP
   IF (to_jsonb(NEW)->>(SELECT attname FROM pg_attribute WHERE attrelid=TG_RELID AND attnum=fk.conkey[i])) IS NULL THEN predicate:=NULL; EXIT; END IF;
   predicate:=predicate || CASE WHEN i=1 THEN '' ELSE ' AND ' END ||
     (SELECT format('%I=($1->>%L)::%s',parent.attname,child.attname,format_type(parent.atttypid,parent.atttypmod))
      FROM pg_attribute parent,pg_attribute child WHERE parent.attrelid=fk.confrelid AND parent.attnum=fk.confkey[i] AND child.attrelid=TG_RELID AND child.attnum=fk.conkey[i]);
  END LOOP;
  IF predicate IS NOT NULL THEN
   EXECUTE format('SELECT true,tenant_id FROM %I.%I WHERE %s',fk.nspname,fk.relname,predicate) INTO present,parent_tenant USING to_jsonb(NEW);
   IF present IS DISTINCT FROM true OR (parent_tenant IS DISTINCT FROM scope_id AND NOT (parent_tenant IS NULL AND TG_TABLE_NAME='workflow_bindings' AND fk.relname='workflows')) THEN
    RAISE EXCEPTION 'Foreign tenant reference denied' USING ERRCODE='42501';
   END IF;
  END IF;
 END LOOP;
 RETURN NEW;
END $$;
ALTER TABLE taska.issues ADD COLUMN tenant_id uuid;
CREATE INDEX issues_rpc_tenant_idx ON taska.issues(tenant_id);
ALTER TABLE taska.issues ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issues FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issues FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issues FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issues FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issues FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issues FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.project_counters ADD COLUMN tenant_id uuid;
CREATE INDEX project_counters_rpc_tenant_idx ON taska.project_counters(tenant_id);
ALTER TABLE taska.project_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.project_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.project_counters FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.project_counters FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.project_counters FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.project_counters FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.project_counters FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_history ADD COLUMN tenant_id uuid;
CREATE INDEX issue_history_rpc_tenant_idx ON taska.issue_history(tenant_id);
ALTER TABLE taska.issue_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_history FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_history FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_history FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_history FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_history FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_history FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_links ADD COLUMN tenant_id uuid;
CREATE INDEX issue_links_rpc_tenant_idx ON taska.issue_links(tenant_id);
ALTER TABLE taska.issue_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_links FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_links FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_links FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_links FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_links FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_links FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.idempotency_keys ADD COLUMN tenant_id uuid;
CREATE INDEX idempotency_keys_rpc_tenant_idx ON taska.idempotency_keys(tenant_id);
ALTER TABLE taska.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.idempotency_keys FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.idempotency_keys FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.idempotency_keys FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.idempotency_keys FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.idempotency_keys FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_attachments ADD COLUMN tenant_id uuid;
CREATE INDEX issue_attachments_rpc_tenant_idx ON taska.issue_attachments(tenant_id);
ALTER TABLE taska.issue_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_attachments FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_attachments FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_attachments FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_attachments FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_attachments FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_comments ADD COLUMN tenant_id uuid;
CREATE INDEX issue_comments_rpc_tenant_idx ON taska.issue_comments(tenant_id);
ALTER TABLE taska.issue_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_comments FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_comments FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_comments FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_comments FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_comments FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_comments FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_watchers ADD COLUMN tenant_id uuid;
CREATE INDEX issue_watchers_rpc_tenant_idx ON taska.issue_watchers(tenant_id);
ALTER TABLE taska.issue_watchers ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_watchers FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_watchers FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_watchers FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_watchers FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_watchers FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_watchers FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.project_labels ADD COLUMN tenant_id uuid;
CREATE INDEX project_labels_rpc_tenant_idx ON taska.project_labels(tenant_id);
ALTER TABLE taska.project_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.project_labels FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.project_labels FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.project_labels FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.project_labels FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.project_labels FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.project_labels FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_labels ADD COLUMN tenant_id uuid;
CREATE INDEX issue_labels_rpc_tenant_idx ON taska.issue_labels(tenant_id);
ALTER TABLE taska.issue_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_labels FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_labels FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_labels FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_labels FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_labels FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_labels FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_worklogs ADD COLUMN tenant_id uuid;
CREATE INDEX issue_worklogs_rpc_tenant_idx ON taska.issue_worklogs(tenant_id);
ALTER TABLE taska.issue_worklogs ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_worklogs FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_worklogs FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_worklogs FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_worklogs FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_worklogs FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_worklogs FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_type_move_receipts ADD COLUMN tenant_id uuid;
CREATE INDEX issue_type_move_receipts_rpc_tenant_idx ON taska.issue_type_move_receipts(tenant_id);
ALTER TABLE taska.issue_type_move_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_type_move_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_type_move_receipts FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_type_move_receipts FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_type_move_receipts FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_type_move_receipts FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_type_move_receipts FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.workflow_binding_receipts ADD COLUMN tenant_id uuid;
CREATE INDEX workflow_binding_receipts_rpc_tenant_idx ON taska.workflow_binding_receipts(tenant_id);
ALTER TABLE taska.workflow_binding_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.workflow_binding_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.workflow_binding_receipts FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.workflow_binding_receipts FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.workflow_binding_receipts FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.workflow_binding_receipts FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.workflow_binding_receipts FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issues DROP CONSTRAINT issues_issue_key_key;
CREATE UNIQUE INDEX issues_tenant_key_unique ON taska.issues(tenant_id,issue_key) NULLS NOT DISTINCT;
ALTER TABLE taska.idempotency_keys DROP CONSTRAINT idempotency_keys_user_key_uniq;
CREATE UNIQUE INDEX idempotency_keys_tenant_user_key_uniq ON taska.idempotency_keys(tenant_id,user_id,key) NULLS NOT DISTINCT;
