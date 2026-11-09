--liquibase formatted sql
-- changeset taska:0013-trusted-rpc-scope splitStatements:false
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
ALTER TABLE taska.workflows ADD COLUMN tenant_id uuid;
CREATE INDEX workflows_rpc_tenant_idx ON taska.workflows(tenant_id);
ALTER TABLE taska.workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.workflows FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.workflows FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (tenant_id IS NULL AND id='11111111-1111-1111-1111-111111111111'::uuid));
CREATE POLICY rpc_insert ON taska.workflows FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.workflows FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.workflows FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.workflows FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.statuses ADD COLUMN tenant_id uuid;
CREATE INDEX statuses_rpc_tenant_idx ON taska.statuses(tenant_id);
ALTER TABLE taska.statuses ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.statuses FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.statuses FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (tenant_id IS NULL AND EXISTS(SELECT 1 FROM taska.workflows w WHERE w.id=statuses.workflow_id AND w.tenant_id IS NULL AND w.id='11111111-1111-1111-1111-111111111111'::uuid)));
CREATE POLICY rpc_insert ON taska.statuses FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.statuses FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.statuses FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.statuses FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.transitions ADD COLUMN tenant_id uuid;
CREATE INDEX transitions_rpc_tenant_idx ON taska.transitions(tenant_id);
ALTER TABLE taska.transitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.transitions FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.transitions FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (tenant_id IS NULL AND EXISTS(SELECT 1 FROM taska.workflows w WHERE w.id=transitions.workflow_id AND w.tenant_id IS NULL AND w.id='11111111-1111-1111-1111-111111111111'::uuid)));
CREATE POLICY rpc_insert ON taska.transitions FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.transitions FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.transitions FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.transitions FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.validator_rules ADD COLUMN tenant_id uuid;
CREATE INDEX validator_rules_rpc_tenant_idx ON taska.validator_rules(tenant_id);
ALTER TABLE taska.validator_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.validator_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.validator_rules FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (tenant_id IS NULL AND EXISTS(SELECT 1 FROM taska.transitions t JOIN taska.workflows w ON w.id=t.workflow_id WHERE t.id=validator_rules.transition_id AND w.tenant_id IS NULL AND w.id='11111111-1111-1111-1111-111111111111'::uuid)));
CREATE POLICY rpc_insert ON taska.validator_rules FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.validator_rules FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.validator_rules FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.validator_rules FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.workflow_bindings ADD COLUMN tenant_id uuid;
CREATE INDEX workflow_bindings_rpc_tenant_idx ON taska.workflow_bindings(tenant_id);
ALTER TABLE taska.workflow_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.workflow_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.workflow_bindings FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (tenant_id IS NULL AND project_id='00000000-0000-0000-0000-000000000000'::uuid));
CREATE POLICY rpc_insert ON taska.workflow_bindings FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.workflow_bindings FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.workflow_bindings FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.workflow_bindings FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_type_move_workflow_pins ADD COLUMN tenant_id uuid;
CREATE INDEX issue_type_move_workflow_pins_rpc_tenant_idx ON taska.issue_type_move_workflow_pins(tenant_id);
ALTER TABLE taska.issue_type_move_workflow_pins ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_type_move_workflow_pins FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_type_move_workflow_pins FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_type_move_workflow_pins FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_type_move_workflow_pins FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_type_move_workflow_pins FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_type_move_workflow_pins FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.workflow_binding_changes ADD COLUMN tenant_id uuid;
CREATE INDEX workflow_binding_changes_rpc_tenant_idx ON taska.workflow_binding_changes(tenant_id);
ALTER TABLE taska.workflow_binding_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.workflow_binding_changes FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.workflow_binding_changes FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.workflow_binding_changes FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.workflow_binding_changes FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.workflow_binding_changes FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.workflow_binding_changes FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
ALTER TABLE taska.issue_type_creation_workflow_receipts ADD COLUMN tenant_id uuid;
CREATE INDEX issue_type_creation_workflow_receipts_rpc_tenant_idx ON taska.issue_type_creation_workflow_receipts(tenant_id);
ALTER TABLE taska.issue_type_creation_workflow_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE taska.issue_type_creation_workflow_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY rpc_read ON taska.issue_type_creation_workflow_receipts FOR SELECT USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) OR (false));
CREATE POLICY rpc_insert ON taska.issue_type_creation_workflow_receipts FOR INSERT WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_update ON taska.issue_type_creation_workflow_receipts FOR UPDATE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)) WITH CHECK((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE POLICY rpc_delete ON taska.issue_type_creation_workflow_receipts FOR DELETE USING((current_setting('app.rpc_scope_enforced',true) IS DISTINCT FROM 'true') OR (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid));
CREATE TRIGGER rpc_scope BEFORE INSERT OR UPDATE ON taska.issue_type_creation_workflow_receipts FOR EACH ROW EXECUTE FUNCTION taska.enforce_legacy_rpc_scope();
