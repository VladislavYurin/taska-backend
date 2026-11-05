SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.free_eligibility_ledger (
 email_digest bytea NOT NULL CHECK(octet_length(email_digest)=32),
 digest_key_version integer NOT NULL CHECK(digest_key_version>0),
 first_user_id uuid, bound_tenant_id uuid,
 consumed_at timestamptz NOT NULL, operation_id uuid UNIQUE,
 purpose_version text NOT NULL, review_due_at timestamptz NOT NULL,
 PRIMARY KEY(digest_key_version,email_digest),
 CHECK(bound_tenant_id IS NOT NULL OR (first_user_id IS NULL AND operation_id IS NULL)),
 CHECK(review_due_at>consumed_at)
);
CREATE INDEX free_eligibility_tenant_idx ON taska.free_eligibility_ledger(bound_tenant_id) WHERE bound_tenant_id IS NOT NULL;
CREATE INDEX free_eligibility_user_idx ON taska.free_eligibility_ledger(first_user_id) WHERE first_user_id IS NOT NULL;
CREATE INDEX free_eligibility_review_idx ON taska.free_eligibility_ledger(review_due_at,digest_key_version);
CREATE TABLE taska.free_eligibility_dispositions (
 case_id uuid PRIMARY KEY, decision_version text NOT NULL, action text NOT NULL CHECK(action IN ('MINIMIZE_ACCOUNT','MINIMIZE_TENANT','REVIEW','ERASE')),
 applied_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION taska.protect_free_marker() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE purpose text:=COALESCE(current_setting('taska.free_disposition',true),'');
BEGIN
 IF TG_OP='DELETE' THEN
   IF purpose IS DISTINCT FROM 'ERASE' THEN RAISE EXCEPTION 'Explicit lawful disposition required' USING ERRCODE='23514'; END IF;
   RETURN OLD;
 END IF;
 IF (NEW.email_digest,NEW.digest_key_version,NEW.consumed_at) IS DISTINCT FROM (OLD.email_digest,OLD.digest_key_version,OLD.consumed_at)
 OR (NEW.bound_tenant_id IS DISTINCT FROM OLD.bound_tenant_id AND (NEW.bound_tenant_id IS NOT NULL OR purpose IS DISTINCT FROM 'MINIMIZE_TENANT'))
 OR (NEW.first_user_id IS DISTINCT FROM OLD.first_user_id AND NOT
   ((NEW.first_user_id IS NULL AND purpose IN ('MINIMIZE_ACCOUNT','MINIMIZE_TENANT')) OR (OLD.first_user_id IS NULL AND NEW.bound_tenant_id IS NOT NULL)))
 OR (NEW.operation_id IS DISTINCT FROM OLD.operation_id AND NOT(NEW.operation_id IS NULL AND purpose='MINIMIZE_TENANT'))
 OR ((NEW.purpose_version,NEW.review_due_at) IS DISTINCT FROM (OLD.purpose_version,OLD.review_due_at) AND purpose IS DISTINCT FROM 'REVIEW') THEN
   RAISE EXCEPTION 'Immutable FREE history or unauthorized disposition' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_free_marker BEFORE UPDATE OR DELETE ON taska.free_eligibility_ledger FOR EACH ROW EXECUTE FUNCTION taska.protect_free_marker();
CREATE FUNCTION taska.guard_free_tenant_deletion() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM taska.free_eligibility_ledger WHERE bound_tenant_id=OLD.id) THEN
   RAISE EXCEPTION 'FREE binding must be minimized by authorized cleanup first' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER free_tenant_delete_guard BEFORE DELETE ON taska.tenants FOR EACH ROW EXECUTE FUNCTION taska.guard_free_tenant_deletion();
CREATE TRIGGER free_tenant_terminal_guard BEFORE UPDATE OF lifecycle ON taska.tenants FOR EACH ROW
 WHEN(NEW.lifecycle='DELETED' AND OLD.lifecycle IS DISTINCT FROM NEW.lifecycle) EXECUTE FUNCTION taska.guard_free_tenant_deletion();
CREATE FUNCTION taska.protect_free_disposition() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable FREE disposition receipt' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_free_disposition BEFORE UPDATE OR DELETE ON taska.free_eligibility_dispositions FOR EACH ROW EXECUTE FUNCTION taska.protect_free_disposition();
REVOKE ALL ON taska.free_eligibility_ledger,taska.free_eligibility_dispositions FROM PUBLIC,taska_tenant_app;
GRANT SELECT,INSERT,DELETE ON taska.free_eligibility_ledger TO taska_tenant_app;
GRANT UPDATE(first_user_id,bound_tenant_id,operation_id,purpose_version,review_due_at) ON taska.free_eligibility_ledger TO taska_tenant_app;
GRANT SELECT,INSERT ON taska.free_eligibility_dispositions TO taska_tenant_app;
