-- Published global configuration is separate from every tenant's mutable working graph.
CREATE TABLE taska.workflow_templates(
 id uuid PRIMARY KEY, code text NOT NULL, version bigint NOT NULL CHECK(version>0),
 name text NOT NULL, graph_schema_version integer NOT NULL CHECK(graph_schema_version>0),
 graph jsonb NOT NULL CHECK(jsonb_typeof(graph)='object'), published_at timestamptz,
 UNIQUE(code,version)
);
CREATE TABLE taska.tenant_workflow_templates(
 tenant_id uuid NOT NULL, id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 template_id uuid NOT NULL REFERENCES taska.workflow_templates(id),
 code text NOT NULL, version bigint NOT NULL CHECK(version>0), name text NOT NULL,
 graph_schema_version integer NOT NULL, graph jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,template_id), UNIQUE(tenant_id,code,version)
);
CREATE TABLE taska.workflow_template_instances(
 tenant_id uuid NOT NULL, project_id uuid NOT NULL,
 template_copy_id uuid NOT NULL, workflow_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,project_id,template_copy_id), UNIQUE(tenant_id,workflow_id),
 FOREIGN KEY(tenant_id,template_copy_id) REFERENCES taska.tenant_workflow_templates(tenant_id,id),
 -- Reserve the unique identity first, then populate the graph in this same transaction.
 FOREIGN KEY(tenant_id,project_id,workflow_id) REFERENCES taska.workflows(tenant_id,owner_project_id,id)
   DEFERRABLE INITIALLY DEFERRED
);
CREATE FUNCTION taska.immutable_workflow_template() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable workflow template or instantiation receipt' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_template BEFORE UPDATE OR DELETE ON taska.workflow_templates FOR EACH ROW EXECUTE FUNCTION taska.immutable_workflow_template();
CREATE TRIGGER immutable_copy BEFORE UPDATE OR DELETE ON taska.tenant_workflow_templates FOR EACH ROW EXECUTE FUNCTION taska.immutable_workflow_template();
CREATE TRIGGER immutable_instance BEFORE UPDATE OR DELETE ON taska.workflow_template_instances FOR EACH ROW EXECUTE FUNCTION taska.immutable_workflow_template();
REVOKE ALL ON taska.workflow_templates FROM PUBLIC;
GRANT SELECT ON taska.workflow_templates TO taska_workflow_app,taska_workflow_admin;

CREATE FUNCTION taska.workflow_original_authority() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.grant_id IS DISTINCT FROM OLD.grant_id OR NEW.actor_auth_version<>OLD.actor_auth_version
    OR NEW.membership_version<>OLD.membership_version
    OR (to_jsonb(NEW)->'actor_user_id') IS DISTINCT FROM (to_jsonb(OLD)->'actor_user_id') THEN
  RAISE EXCEPTION 'Original workflow operation authority is immutable' USING ERRCODE='23514';
 END IF;
 IF NEW.operation_fence<>OLD.operation_fence AND NEW.operation_fence<>OLD.operation_fence+1 THEN
  RAISE EXCEPTION 'Workflow operation fence must advance by one' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER original_authority BEFORE UPDATE ON taska.workflow_binding_changes FOR EACH ROW EXECUTE FUNCTION taska.workflow_original_authority();
CREATE TRIGGER original_authority BEFORE UPDATE ON taska.issue_type_move_workflow_pins FOR EACH ROW EXECUTE FUNCTION taska.workflow_original_authority();

INSERT INTO taska.workflow_templates(id,code,version,name,graph_schema_version,graph,published_at) VALUES ('11111111-1111-1111-1111-111111111111','DEFAULT',1,'Default workflow',1,'{"statuses":[{"key":"TODO","name":"To Do","category":"TODO","sortOrder":10},{"key":"IN_PROGRESS","name":"In Progress","category":"IN_PROGRESS","sortOrder":20},{"key":"DONE","name":"Done","category":"DONE","sortOrder":30}],"transitions":[{"from":"TODO","to":"IN_PROGRESS","name":"Start Progress","hidden":false,"sortOrder":10,"rules":[]},{"from":"IN_PROGRESS","to":"DONE","name":"Complete","hidden":false,"sortOrder":20,"rules":[]},{"from":"DONE","to":"IN_PROGRESS","name":"Reopen","hidden":false,"sortOrder":30,"rules":[]},{"from":"IN_PROGRESS","to":"TODO","name":"Stop Progress","hidden":false,"sortOrder":40,"rules":[]},{"from":"TODO","to":"DONE","name":"Close","hidden":false,"sortOrder":50,"rules":[]},{"from":"DONE","to":"TODO","name":"Back to To Do","hidden":false,"sortOrder":60,"rules":[]}]}'::jsonb,now());
