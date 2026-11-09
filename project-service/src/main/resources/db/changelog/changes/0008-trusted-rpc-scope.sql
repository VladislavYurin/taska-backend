--liquibase formatted sql
-- changeset taska:0008-trusted-rpc-scope splitStatements:false
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
ALTER TABLE taska.projects ADD COLUMN tenant_id uuid;
CREATE INDEX projects_rpc_tenant_idx ON taska.projects(tenant_id);
ALTER TABLE taska.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.projects FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.projects FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.projects FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.projects FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.projects FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.projects FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.project_members ADD COLUMN tenant_id uuid;
CREATE INDEX project_members_rpc_tenant_idx ON taska.project_members(tenant_id);
ALTER TABLE taska.project_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.project_members FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.project_members FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.project_members FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.project_members FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.project_members FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.project_members FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.project_settings ADD COLUMN tenant_id uuid;
CREATE INDEX project_settings_rpc_tenant_idx ON taska.project_settings(tenant_id);
ALTER TABLE taska.project_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.project_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.project_settings FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.project_settings FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.project_settings FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.project_settings FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.project_settings FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.project_issue_types ADD COLUMN tenant_id uuid;
CREATE INDEX project_issue_types_rpc_tenant_idx ON taska.project_issue_types(tenant_id);
ALTER TABLE taska.project_issue_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.project_issue_types FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.project_issue_types FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.project_issue_types FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.project_issue_types FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.project_issue_types FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.project_issue_types FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_type_deletions ADD COLUMN tenant_id uuid;
CREATE INDEX issue_type_deletions_rpc_tenant_idx ON taska.issue_type_deletions(tenant_id);
ALTER TABLE taska.issue_type_deletions ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_type_deletions FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_type_deletions FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_type_deletions FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_type_deletions FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_type_deletions FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_type_deletions FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.project_type_operations ADD COLUMN tenant_id uuid;
CREATE INDEX project_type_operations_rpc_tenant_idx ON taska.project_type_operations(tenant_id);
ALTER TABLE taska.project_type_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.project_type_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.project_type_operations FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.project_type_operations FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.project_type_operations FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.project_type_operations FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.project_type_operations FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_type_creations ADD COLUMN tenant_id uuid;
CREATE INDEX issue_type_creations_rpc_tenant_idx ON taska.issue_type_creations(tenant_id);
ALTER TABLE taska.issue_type_creations ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_type_creations FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_type_creations FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_type_creations FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_type_creations FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_type_creations FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_type_creations FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.projects DROP CONSTRAINT projects_project_key_key;
CREATE UNIQUE INDEX projects_tenant_key_unique ON taska.projects(tenant_id,project_key) NULLS NOT DISTINCT;
