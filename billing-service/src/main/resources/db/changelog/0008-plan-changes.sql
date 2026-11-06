SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
ALTER TABLE taska.subscriptions ADD COLUMN selection_generation bigint NOT NULL DEFAULT 0 CHECK(selection_generation>=0),
 ADD COLUMN active_plan_change_id uuid;
CREATE TABLE taska.subscription_plan_changes (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id),
 actor_id uuid NOT NULL,request_key varchar(128) NOT NULL,request_hash bytea NOT NULL,
 owner_id uuid NOT NULL,owner_version bigint NOT NULL CHECK(owner_version>0),
 requested_version bigint NOT NULL CHECK(requested_version>0),selection_generation bigint NOT NULL CHECK(selection_generation>=0),
 previous_plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id),
 target_plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id),
 target_terms_snapshot jsonb NOT NULL CHECK(jsonb_typeof(target_terms_snapshot)='object'),
 effect text NOT NULL CHECK(effect IN ('IMMEDIATE','AFTER_PAYMENT','NEXT_PERIOD')),
 state text NOT NULL CHECK(state IN ('PREPARED','AWAITING_PAYMENT','SCHEDULED','APPLIED','SUPERSEDED','REJECTED')),
 previous_change_id uuid,failure_code text,
 currency char(3) CHECK(currency IN ('EUR','USD')),payment_method text CHECK(payment_method IN ('PSP','WIRE')),
 effective_at timestamptz,response text,created_at timestamptz NOT NULL,applied_at timestamptz,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,actor_id,request_key),
 FOREIGN KEY(tenant_id,previous_change_id) REFERENCES taska.subscription_plan_changes(tenant_id,id),
 CHECK(effect='IMMEDIATE' OR (currency IS NOT NULL AND payment_method IS NOT NULL)),
 CHECK(state<>'APPLIED' OR applied_at IS NOT NULL),CHECK(state<>'REJECTED' OR failure_code IS NOT NULL)
);
ALTER TABLE taska.subscriptions ADD CONSTRAINT subscription_active_plan_change_fk
 FOREIGN KEY(tenant_id,active_plan_change_id) REFERENCES taska.subscription_plan_changes(tenant_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX subscription_free_recovery ON taska.subscription_plan_changes(tenant_id,created_at,id) WHERE state='PREPARED' AND effect='IMMEDIATE';
CREATE FUNCTION taska.protect_plan_change_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Plan change history must survive';END IF;
 IF (to_jsonb(NEW)-ARRAY['state','response','applied_at','failure_code']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','response','applied_at','failure_code'])
 OR (OLD.response IS NOT NULL AND NEW.response IS DISTINCT FROM OLD.response)
 OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at)
 OR (OLD.failure_code IS NOT NULL AND NEW.failure_code IS DISTINCT FROM OLD.failure_code)
 OR (OLD.state IN ('APPLIED','SUPERSEDED','REJECTED') AND NEW.state<>OLD.state)
 OR (OLD.state='PREPARED' AND NEW.state NOT IN ('PREPARED','APPLIED','REJECTED'))
 THEN RAISE EXCEPTION 'Immutable plan change identity/receipt';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_plan_change BEFORE UPDATE OR DELETE ON taska.subscription_plan_changes FOR EACH ROW EXECUTE FUNCTION taska.protect_plan_change_identity();

ALTER TABLE taska.subscription_periods ADD COLUMN selection_generation bigint NOT NULL DEFAULT 0 CHECK(selection_generation>=0), ADD COLUMN plan_change_id uuid;
ALTER TABLE taska.subscription_periods ADD CONSTRAINT period_plan_change_fk FOREIGN KEY(tenant_id,plan_change_id) REFERENCES taska.subscription_plan_changes(tenant_id,id);
CREATE UNIQUE INDEX one_period_per_plan_change ON taska.subscription_periods(tenant_id,plan_change_id) WHERE plan_change_id IS NOT NULL;
CREATE UNIQUE INDEX one_pending_free_change ON taska.subscription_plan_changes(tenant_id) WHERE state='PREPARED';
