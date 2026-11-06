-- Only the opt-in clean-start changelog includes this migration. No legacy tenant is invented.
SET LOCAL ROLE taska_project_migrator;
SET LOCAL search_path=pg_catalog;
DO $empty$
DECLARE t text; has_rows boolean;
BEGIN
  FOREACH t IN ARRAY ARRAY['projects','project_members','project_settings','project_issue_types',
      'project_type_operations','issue_type_creations','issue_type_deletions','outbox_events'] LOOP
    IF EXISTS (SELECT FROM pg_class WHERE oid=format('taska.%I',t)::regclass AND relrowsecurity) THEN
      RAISE EXCEPTION 'Tenant migration requires unprotected empty tables';
    END IF;
    EXECUTE format('SELECT EXISTS(SELECT FROM taska.%I)',t) INTO STRICT has_rows;
    IF has_rows THEN RAISE EXCEPTION 'Tenant clean start requires empty business tables'; END IF;
    EXECUTE format('ALTER TABLE taska.%I ADD COLUMN tenant_id uuid NOT NULL',t);
  END LOOP;
END $empty$;

ALTER TABLE taska.project_members DROP CONSTRAINT project_members_project_id_fkey;
ALTER TABLE taska.project_settings DROP CONSTRAINT project_settings_project_id_fkey;
ALTER TABLE taska.project_issue_types DROP CONSTRAINT project_issue_types_project_id_fkey;
ALTER TABLE taska.project_issue_types DROP CONSTRAINT project_issue_types_pending_operation_id_fkey;
ALTER TABLE taska.project_type_operations DROP CONSTRAINT project_type_operations_project_id_fkey;
ALTER TABLE taska.issue_type_creations DROP CONSTRAINT issue_type_creations_project_id_fkey;
ALTER TABLE taska.issue_type_creations DROP CONSTRAINT issue_type_creations_id_fkey;
ALTER TABLE taska.issue_type_deletions DROP CONSTRAINT issue_type_deletions_project_id_fkey;

ALTER TABLE taska.projects DROP CONSTRAINT projects_project_key_key;
ALTER TABLE taska.projects ADD UNIQUE(tenant_id,id), ADD UNIQUE(tenant_id,project_key);
ALTER TABLE taska.project_type_operations ADD UNIQUE(tenant_id,id), ADD UNIQUE(tenant_id,project_id,id);
ALTER TABLE taska.issue_type_deletions ADD UNIQUE(tenant_id,id);
ALTER TABLE taska.issue_type_creations ADD UNIQUE(tenant_id,id);
ALTER TABLE taska.outbox_events ADD UNIQUE(tenant_id,id);
ALTER TABLE taska.project_members DROP CONSTRAINT project_members_pkey,
  ADD PRIMARY KEY(tenant_id,project_id,user_id),
  ADD FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id);
ALTER TABLE taska.project_settings DROP CONSTRAINT project_settings_pkey,
  ADD PRIMARY KEY(tenant_id,project_id),
  ADD FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id);
ALTER TABLE taska.project_issue_types DROP CONSTRAINT project_issue_types_pkey,
  ADD PRIMARY KEY(tenant_id,project_id,code),
  ADD FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id),
  ADD FOREIGN KEY(tenant_id,project_id,pending_operation_id)
    REFERENCES taska.project_type_operations(tenant_id,project_id,id);
ALTER TABLE taska.project_type_operations
  ADD FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id);
ALTER TABLE taska.issue_type_creations
  DROP CONSTRAINT issue_type_creations_project_id_actor_user_id_request_id_key,
  ADD UNIQUE(tenant_id,project_id,actor_user_id,request_id),
  ADD FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id),
  ADD FOREIGN KEY(tenant_id,project_id,id) REFERENCES taska.project_type_operations(tenant_id,project_id,id);
ALTER TABLE taska.issue_type_deletions
  ADD FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id),
  ADD FOREIGN KEY(tenant_id,project_id,id) REFERENCES taska.project_type_operations(tenant_id,project_id,id);

DROP INDEX taska.projects_created_by_idx;
DROP INDEX taska.project_members_user_idx;
DROP INDEX taska.project_members_project_role_idx;
DROP INDEX taska.project_issue_types_order_idx;
DROP INDEX taska.issue_type_one_pending_deletion_per_project;
DROP INDEX taska.issue_type_deletion_lifecycle_idx;
DROP INDEX taska.issue_type_deletion_recovery_idx;
DROP INDEX taska.project_type_one_active_operation;
DROP INDEX taska.issue_type_creation_recovery;
DROP INDEX taska.outbox_events_unpublished_idx;
DROP INDEX taska.outbox_events_aggregate_idx;
DROP INDEX taska.outbox_events_status_created_idx;
DROP INDEX taska.outbox_events_processing_idx;
CREATE INDEX projects_created_by_idx ON taska.projects(tenant_id,created_by,created_at,id);
CREATE INDEX projects_list_idx ON taska.projects(tenant_id,created_at,id) WHERE archived_at IS NULL;
CREATE INDEX project_members_user_idx ON taska.project_members(tenant_id,user_id,project_id);
CREATE INDEX project_members_project_role_idx ON taska.project_members(tenant_id,project_id,role);
CREATE INDEX project_issue_types_order_idx ON taska.project_issue_types(tenant_id,project_id,sort_order,code COLLATE "C");
CREATE UNIQUE INDEX issue_type_one_pending_deletion_per_project
  ON taska.issue_type_deletions(tenant_id,project_id) WHERE state IN ('PENDING','CANCELLING');
CREATE INDEX issue_type_deletion_lifecycle_idx ON taska.issue_type_deletions(tenant_id,project_id,source_code,source_generation);
CREATE INDEX issue_type_deletion_recovery_idx ON taska.issue_type_deletions(tenant_id,updated_at,id) WHERE reservation_released=false;
CREATE UNIQUE INDEX project_type_one_active_operation ON taska.project_type_operations(tenant_id,project_id) WHERE active;
CREATE INDEX issue_type_creation_recovery ON taska.issue_type_creations(tenant_id,updated_at,id) WHERE state IN ('PENDING','CANCELLING');
CREATE INDEX outbox_events_aggregate_idx ON taska.outbox_events(tenant_id,aggregate_id,created_at,id);
CREATE INDEX outbox_events_status_created_idx ON taska.outbox_events(tenant_id,status,created_at,id) WHERE status IN ('NEW','PROCESSING');

-- TAS-459 owns irreversible deletion. Archive retains all rows, UUIDs and quota usage.
CREATE FUNCTION taska.project_no_delete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Project deletion is not enabled' USING ERRCODE='23514'; END $$;
CREATE TRIGGER project_no_delete BEFORE DELETE ON taska.projects FOR EACH ROW EXECUTE FUNCTION taska.project_no_delete();

ALTER TABLE taska.project_type_operations ADD COLUMN grant_id uuid, ADD COLUMN actor_auth_version bigint NOT NULL CHECK(actor_auth_version>0), ADD COLUMN membership_version bigint NOT NULL CHECK(membership_version>0), ADD COLUMN operation_fence bigint NOT NULL DEFAULT 1 CHECK(operation_fence>0);
ALTER TABLE taska.issue_type_creations ADD COLUMN grant_id uuid, ADD COLUMN actor_auth_version bigint NOT NULL CHECK(actor_auth_version>0), ADD COLUMN membership_version bigint NOT NULL CHECK(membership_version>0), ADD COLUMN operation_fence bigint NOT NULL DEFAULT 1 CHECK(operation_fence>0);
ALTER TABLE taska.issue_type_deletions ADD COLUMN grant_id uuid, ADD COLUMN actor_auth_version bigint NOT NULL CHECK(actor_auth_version>0), ADD COLUMN membership_version bigint NOT NULL CHECK(membership_version>0), ADD COLUMN operation_fence bigint NOT NULL DEFAULT 1 CHECK(operation_fence>0);

CREATE FUNCTION taska.project_original_authority() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF NEW.grant_id IS DISTINCT FROM OLD.grant_id OR NEW.actor_auth_version<>OLD.actor_auth_version
     OR NEW.membership_version<>OLD.membership_version
     OR (to_jsonb(NEW)->'actor_user_id') IS DISTINCT FROM (to_jsonb(OLD)->'actor_user_id') THEN
    RAISE EXCEPTION 'Original project operation authority is immutable' USING ERRCODE='23514';
  END IF;
  IF NEW.operation_fence<>OLD.operation_fence AND NEW.operation_fence<>OLD.operation_fence+1 THEN
    RAISE EXCEPTION 'Project operation fence must advance by one' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER original_authority BEFORE UPDATE ON taska.project_type_operations FOR EACH ROW EXECUTE FUNCTION taska.project_original_authority();
CREATE TRIGGER original_authority BEFORE UPDATE ON taska.issue_type_creations FOR EACH ROW EXECUTE FUNCTION taska.project_original_authority();
CREATE TRIGGER original_authority BEFORE UPDATE ON taska.issue_type_deletions FOR EACH ROW EXECUTE FUNCTION taska.project_original_authority();
