SET LOCAL ROLE taska_tenant_migrator;
CREATE TABLE taska.free_plan_admissions (
 tenant_id uuid NOT NULL REFERENCES taska.tenants(id),operation_id uuid NOT NULL,
 owner_id uuid,owner_version bigint NOT NULL CHECK(owner_version>0),
 state text NOT NULL DEFAULT 'ADMITTED' CHECK(state IN ('ADMITTED','CANCELLED')),
 accepted_at timestamptz,cancelled_at timestamptz,
 CHECK((state='ADMITTED' AND accepted_at IS NOT NULL AND cancelled_at IS NULL) OR (state='CANCELLED' AND cancelled_at IS NOT NULL AND accepted_at IS NULL)),
 PRIMARY KEY(tenant_id,operation_id)
);
CREATE FUNCTION taska.immutable_free_plan_admission() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE purpose text:=COALESCE(current_setting('taska.free_disposition',true),'');
BEGIN
 IF TG_OP='DELETE' AND purpose='MINIMIZE_TENANT' THEN RETURN OLD; END IF;
 IF TG_OP='UPDATE' AND purpose IN ('MINIMIZE_ACCOUNT','MINIMIZE_TENANT')
   AND NEW.owner_id IS NULL
   AND (to_jsonb(NEW)-'owner_id') IS NOT DISTINCT FROM (to_jsonb(OLD)-'owner_id') THEN RETURN NEW; END IF;
 RAISE EXCEPTION 'Accepted FREE admission is immutable outside explicit lawful disposition';
END $$;
CREATE TRIGGER immutable_free_plan_admission BEFORE UPDATE OR DELETE ON taska.free_plan_admissions FOR EACH ROW EXECUTE FUNCTION taska.immutable_free_plan_admission();

-- Routing IDs only, like tenant_provisioning_dispatch; never financial or eligibility proof.
-- This index lets the authorized global disposition visit each ordinary FORCE-RLS scope.
CREATE TABLE taska.free_admission_subjects(owner_id uuid NOT NULL,tenant_id uuid NOT NULL,PRIMARY KEY(owner_id,tenant_id));
REVOKE ALL ON taska.free_admission_subjects FROM PUBLIC,taska_tenant_app;
GRANT SELECT,INSERT,DELETE ON taska.free_admission_subjects TO taska_tenant_app;
CREATE FUNCTION taska.protect_free_admission_subject() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' AND ((current_setting('taska.free_disposition',true)='MINIMIZE_ACCOUNT' AND OLD.owner_id=NULLIF(current_setting('taska.free_disposition_subject',true),'')::uuid)
 OR (current_setting('taska.free_disposition',true)='MINIMIZE_TENANT' AND OLD.tenant_id=NULLIF(current_setting('taska.free_disposition_subject',true),'')::uuid)) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Explicit subject-bound lawful disposition required';
END $$;
CREATE TRIGGER immutable_free_admission_subject BEFORE UPDATE OR DELETE ON taska.free_admission_subjects FOR EACH ROW EXECUTE FUNCTION taska.protect_free_admission_subject();
