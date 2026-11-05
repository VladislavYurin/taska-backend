-- TAS-330 clean start only. Existing business tables are never reassigned or reset.
SET LOCAL ROLE taska_workflow_migrator;
SET LOCAL search_path=pg_catalog;
DO $fresh$ BEGIN
 IF EXISTS(SELECT FROM pg_class WHERE relnamespace='taska'::regnamespace AND relkind IN ('r','p')) THEN
  RAISE EXCEPTION 'Workflow tenant storage requires a fresh service database';
 END IF;
END $fresh$;
CREATE TABLE taska.workflows(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  name text NOT NULL,
  version integer NOT NULL DEFAULT 1,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  owner_project_id uuid NOT NULL,
  PRIMARY KEY (id),
  CHECK (version >= 1),
  UNIQUE (tenant_id, id),
  UNIQUE(tenant_id,owner_project_id,id),
  CHECK(owner_project_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE taska.statuses(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  workflow_id uuid NOT NULL,
  status_key text NOT NULL,
  name text NOT NULL,
  category text NOT NULL,
  sort_order integer NOT NULL DEFAULT 0,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  PRIMARY KEY (id),
  CHECK (sort_order >= 0),
  UNIQUE (tenant_id, workflow_id, status_key),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id,workflow_id,id),
  CHECK(category IN ('TODO','IN_PROGRESS','DONE'))
);
CREATE TABLE taska.transitions(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  workflow_id uuid NOT NULL,
  from_status_id uuid NOT NULL,
  to_status_id uuid NOT NULL,
  name text NOT NULL,
  is_hidden boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 0,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  CHECK (from_status_id <> to_status_id),
  PRIMARY KEY (id),
  CHECK (sort_order >= 0),
  UNIQUE (tenant_id, id)
);
CREATE TABLE taska.validator_rules(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  transition_id uuid NOT NULL,
  rule_type text NOT NULL,
  config jsonb NOT NULL DEFAULT '{}'::jsonb,
  error_code text,
  error_message text,
  sort_order integer NOT NULL DEFAULT 0,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  PRIMARY KEY (id),
  CHECK (rule_type = ANY (ARRAY['REQUIRE_FIELD'::text, 'REQUIRE_ASSIGNEE'::text, 'REQUIRE_COMMENT'::text])),
  CHECK (sort_order >= 0),
  UNIQUE (tenant_id, transition_id, rule_type, sort_order),
  UNIQUE (tenant_id, id)
);
CREATE TABLE taska.workflow_bindings(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  project_id uuid NOT NULL,
  issue_type text NOT NULL,
  workflow_id uuid NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  issue_type_generation uuid NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, project_id, issue_type),
  UNIQUE (tenant_id, id),
  CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE taska.issue_type_move_workflow_pins(
  tenant_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  project_id uuid NOT NULL,
  workflow_id uuid NOT NULL,
  valid_status_keys text[] NOT NULL,
  released boolean NOT NULL DEFAULT false,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  grant_id uuid,
  actor_auth_version bigint NOT NULL,
  membership_version bigint NOT NULL,
  operation_fence bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (operation_id),
  UNIQUE (tenant_id, operation_id),
  CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid),
  CHECK(actor_auth_version>0 AND membership_version>0 AND operation_fence>0)
);
CREATE TABLE taska.workflow_binding_changes(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  project_id uuid NOT NULL,
  workflow_id uuid NOT NULL,
  actor_user_id uuid NOT NULL,
  kind text NOT NULL,
  request_id text NOT NULL,
  node_id text NOT NULL,
  channel character varying(32),
  token_id uuid,
  command_json jsonb NOT NULL,
  plan_json jsonb NOT NULL,
  state text NOT NULL,
  error_status text,
  error_message text,
  error_details jsonb NOT NULL DEFAULT '{}'::jsonb,
  result_bytes bytea,
  reservation_released boolean NOT NULL DEFAULT false,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  grant_id uuid,
  actor_auth_version bigint NOT NULL,
  membership_version bigint NOT NULL,
  operation_fence bigint NOT NULL DEFAULT 1,
  CHECK (channel IS NULL OR (channel::text = ANY (ARRAY['UI'::character varying, 'API_TOKEN'::character varying, 'MCP'::character varying]::text[]))),
  CHECK (kind = ANY (ARRAY['CREATE'::text, 'REPLACE_TYPES'::text])),
  PRIMARY KEY (id),
  UNIQUE (tenant_id, project_id, actor_user_id, request_id),
  CHECK (state = ANY (ARRAY['PENDING'::text, 'CANCELLING'::text, 'COMPLETED'::text, 'CANCELLED'::text])),
  UNIQUE (tenant_id, id),
  CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid),
  CHECK(actor_auth_version>0 AND membership_version>0 AND operation_fence>0)
);
CREATE TABLE taska.issue_type_creation_workflow_receipts(
  tenant_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  project_id uuid NOT NULL,
  state text NOT NULL,
  workflow_id uuid,
  code text,
  generation uuid,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  grant_id uuid,
  actor_auth_version bigint NOT NULL,
  membership_version bigint NOT NULL,
  operation_fence bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (operation_id),
  CHECK (state = ANY (ARRAY['APPLIED'::text, 'CANCELLED'::text])),
  UNIQUE (tenant_id, operation_id),
  CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid),
  CHECK(actor_auth_version>0 AND membership_version>0 AND operation_fence>0),
  CHECK(state<>'APPLIED' OR (workflow_id IS NOT NULL AND code IS NOT NULL AND generation IS NOT NULL))
);
ALTER TABLE taska.statuses ADD FOREIGN KEY(tenant_id,workflow_id) REFERENCES taska.workflows(tenant_id,id) ON DELETE CASCADE;
ALTER TABLE taska.transitions ADD FOREIGN KEY(tenant_id,workflow_id) REFERENCES taska.workflows(tenant_id,id) ON DELETE CASCADE;
ALTER TABLE taska.transitions ADD FOREIGN KEY(tenant_id,workflow_id,from_status_id) REFERENCES taska.statuses(tenant_id,workflow_id,id) ON DELETE CASCADE;
ALTER TABLE taska.transitions ADD FOREIGN KEY(tenant_id,workflow_id,to_status_id) REFERENCES taska.statuses(tenant_id,workflow_id,id) ON DELETE CASCADE;
ALTER TABLE taska.validator_rules ADD FOREIGN KEY(tenant_id,transition_id) REFERENCES taska.transitions(tenant_id,id) ON DELETE CASCADE;
ALTER TABLE taska.workflow_bindings ADD FOREIGN KEY(tenant_id,project_id,workflow_id) REFERENCES taska.workflows(tenant_id,owner_project_id,id);
CREATE INDEX issue_type_creation_workflow_receipts_tenant_idx ON taska.issue_type_creation_workflow_receipts USING btree (tenant_id);
CREATE INDEX issue_type_move_workflow_pins_tenant_idx ON taska.issue_type_move_workflow_pins USING btree (tenant_id);
CREATE INDEX statuses_workflow_idx ON taska.statuses USING btree (tenant_id, workflow_id);
CREATE INDEX statuses_tenant_idx ON taska.statuses USING btree (tenant_id);
CREATE INDEX transitions_workflow_from_idx ON taska.transitions USING btree (tenant_id, workflow_id, from_status_id);
CREATE INDEX transitions_workflow_to_idx ON taska.transitions USING btree (tenant_id, workflow_id, to_status_id);
CREATE INDEX transitions_tenant_idx ON taska.transitions USING btree (tenant_id);
CREATE INDEX validator_rules_transition_idx ON taska.validator_rules USING btree (tenant_id, transition_id);
CREATE INDEX validator_rules_tenant_idx ON taska.validator_rules USING btree (tenant_id);
CREATE INDEX workflow_binding_changes_recovery ON taska.workflow_binding_changes USING btree (tenant_id, updated_at, id) WHERE (reservation_released = false);
CREATE INDEX workflow_binding_changes_tenant_idx ON taska.workflow_binding_changes USING btree (tenant_id);
CREATE INDEX workflow_bindings_workflow_idx ON taska.workflow_bindings USING btree (tenant_id, workflow_id);
CREATE INDEX workflow_bindings_tenant_idx ON taska.workflow_bindings USING btree (tenant_id);
CREATE INDEX workflows_owner_project_idx ON taska.workflows USING btree (tenant_id, owner_project_id, created_at, id);
CREATE INDEX workflows_tenant_idx ON taska.workflows USING btree (tenant_id);
CREATE UNIQUE INDEX transitions_workflow_from_to_uniq ON taska.transitions(tenant_id,workflow_id,from_status_id,to_status_id);

CREATE UNIQUE INDEX issue_type_one_workflow_pin_per_project ON taska.issue_type_move_workflow_pins(tenant_id,project_id) WHERE released=false;
CREATE UNIQUE INDEX workflow_binding_changes_pending_project ON taska.workflow_binding_changes(tenant_id,project_id) WHERE state IN ('PENDING','CANCELLING');

-- Pins/journals/terminal receipts retain graph provenance after cancellation or cleanup.
-- Initial writes validate scoped ownership under row locks; historical references are not live FKs.
