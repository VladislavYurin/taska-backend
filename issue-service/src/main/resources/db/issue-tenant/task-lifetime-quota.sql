SET LOCAL ROLE taska_issue_migrator;
ALTER TABLE taska.project_counters ADD COLUMN retired_at timestamptz;
CREATE FUNCTION taska.guard_lifetime_counter() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Lifetime counter tombstones must be retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.project_id<>OLD.project_id OR NEW.lifetime_created<OLD.lifetime_created
   OR NEW.next_issue_number<OLD.next_issue_number OR NEW.version<OLD.version
   OR (OLD.retired_at IS NOT NULL AND (NEW.retired_at IS DISTINCT FROM OLD.retired_at OR NEW.lifetime_created<>OLD.lifetime_created))
 THEN RAISE EXCEPTION 'Lifetime counter is monotonic and retirement is terminal'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER guard_lifetime_counter BEFORE UPDATE OR DELETE ON taska.project_counters FOR EACH ROW EXECUTE FUNCTION taska.guard_lifetime_counter();
CREATE TABLE taska.task_lifetime_quota_state(
 tenant_id uuid PRIMARY KEY,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 entitlement_version bigint NOT NULL DEFAULT 0 CHECK(entitlement_version>=0),
 maximum_created bigint NOT NULL DEFAULT 0 CHECK(maximum_created>=0),
 task_limit bigint CHECK(task_limit>=0),
 unlimited boolean NOT NULL DEFAULT true
);
CREATE TABLE taska.task_quota_outbox(
 tenant_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 entitlement_version bigint NOT NULL CHECK(entitlement_version>=0),
 maximum_created bigint NOT NULL CHECK(maximum_created>=0),
 over_limit boolean,
 request_id text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,version)
);
