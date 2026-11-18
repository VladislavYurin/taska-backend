SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;

CREATE TABLE taska.autopay_mandates (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id),
 owner_id uuid NOT NULL,
 owner_version bigint NOT NULL CHECK(owner_version>0),
 payment_method_id uuid NOT NULL,
 provider text NOT NULL,
 merchant_account text NOT NULL,
 provider_environment text NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
 merchant_profile_version text NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','REVOKED')),
 consent_version bigint NOT NULL CHECK(consent_version>0),
 created_subscription_version bigint NOT NULL CHECK(created_subscription_version>0),
 consented_at timestamptz NOT NULL,
 revoked_at timestamptz,
 revocation_reason text,
 revocation_barrier text NOT NULL,
 consent_terms_version text NOT NULL,
 consent_text text NOT NULL CHECK(length(consent_text) BETWEEN 1 AND 20000),
 configuration_version text NOT NULL CHECK(length(configuration_version) BETWEEN 1 AND 100),
 consent_channel text NOT NULL CHECK(consent_channel IN ('WEB','PAT','OAUTH','MCP')),
 consent_snapshot jsonb NOT NULL CHECK(jsonb_typeof(consent_snapshot)='object'),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,payment_method_id) REFERENCES taska.payment_methods(tenant_id,id),
 CHECK((state='ACTIVE' AND revoked_at IS NULL AND revocation_reason IS NULL) OR
       (state='REVOKED' AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL))
);
CREATE UNIQUE INDEX autopay_one_active ON taska.autopay_mandates(tenant_id) WHERE state='ACTIVE';
CREATE INDEX autopay_history ON taska.autopay_mandates(tenant_id,consented_at DESC,id DESC);

CREATE FUNCTION taska.protect_autopay_mandate() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable autopay consent' USING ERRCODE='23514'; END IF;
 IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
 IF OLD.state<>'ACTIVE' OR NEW.state<>'REVOKED' OR NEW.consent_version<>OLD.consent_version+1 OR
   (to_jsonb(NEW)-ARRAY['state','consent_version','revoked_at','revocation_reason']) IS DISTINCT FROM
   (to_jsonb(OLD)-ARRAY['state','consent_version','revoked_at','revocation_reason']) THEN
   RAISE EXCEPTION 'Immutable consent or invalid revocation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_autopay BEFORE UPDATE OR DELETE ON taska.autopay_mandates
 FOR EACH ROW EXECUTE FUNCTION taska.protect_autopay_mandate();

CREATE FUNCTION taska.revoke_autopay_with_subscription() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.autopay_state='CONSENTED' AND NEW.autopay_state<>'CONSENTED' THEN
   UPDATE taska.autopay_mandates SET state='REVOKED',consent_version=consent_version+1,
     revoked_at=now(),revocation_reason='SUBSCRIPTION_REVOKED' WHERE tenant_id=NEW.tenant_id AND state='ACTIVE';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER revoke_subscription_autopay AFTER UPDATE OF autopay_state ON taska.subscriptions
 FOR EACH ROW EXECUTE FUNCTION taska.revoke_autopay_with_subscription();

CREATE TABLE taska.autopay_dispatches (
 tenant_id uuid NOT NULL,
 invoice_id uuid NOT NULL,
 mandate_id uuid NOT NULL,
 attempt_id uuid NOT NULL,
 source_period_id uuid NOT NULL,
 selection_generation bigint NOT NULL CHECK(selection_generation>=0),
 accepted_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,invoice_id),
 UNIQUE(tenant_id,source_period_id),
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id),
 FOREIGN KEY(tenant_id,mandate_id) REFERENCES taska.autopay_mandates(tenant_id,id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES taska.payment_attempts(tenant_id,id),
 FOREIGN KEY(tenant_id,source_period_id) REFERENCES taska.subscription_periods(tenant_id,id)
);
CREATE FUNCTION taska.protect_autopay_dispatch() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable autopay dispatch' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_autopay_dispatch BEFORE UPDATE OR DELETE ON taska.autopay_dispatches
 FOR EACH ROW EXECUTE FUNCTION taska.protect_autopay_dispatch();

ALTER TABLE taska.payment_attempts ADD CONSTRAINT attempt_mandate_fk
 FOREIGN KEY(tenant_id,mandate_id) REFERENCES taska.autopay_mandates(tenant_id,id);

SELECT set_config('taska.guard_service','billing',true),set_config('taska.guard_schema','taska',true),
 set_config('taska.guard_table','autopay_mandates',true),set_config('taska.guard_column','tenant_id',true),
 set_config('taska.guard_app_access','read_write',true);

CREATE FUNCTION taska.protect_saved_payment_method() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF ROW(NEW.id,NEW.tenant_id,NEW.owner_id,NEW.provider,NEW.provider_customer_ref,NEW.provider_method_ref,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.owner_id,OLD.provider,OLD.provider_customer_ref,OLD.provider_method_ref,OLD.created_at)
   OR (OLD.status='REVOKED' AND NEW.status<>'REVOKED') THEN
   RAISE EXCEPTION 'Immutable saved payment identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_saved_method BEFORE UPDATE ON taska.payment_methods
 FOR EACH ROW EXECUTE FUNCTION taska.protect_saved_payment_method();

CREATE FUNCTION taska.protect_attempt_mandate() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.mandate_id IS DISTINCT FROM OLD.mandate_id AND
   (OLD.mandate_id IS NOT NULL OR OLD.state<>'ACCEPTED' OR NEW.state<>'SUBMITTED' OR
    NOT EXISTS(SELECT 1 FROM taska.autopay_mandates m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.mandate_id AND m.state='ACTIVE')) THEN
   RAISE EXCEPTION 'Immutable recurring payment binding' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_attempt_mandate BEFORE UPDATE ON taska.payment_attempts
 FOR EACH ROW EXECUTE FUNCTION taska.protect_attempt_mandate();
REVOKE DELETE,TRUNCATE ON taska.payment_methods FROM taska_billing_app;

CREATE TABLE taska.autopay_authorization_audit (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,actor_id uuid NOT NULL,owner_id uuid NOT NULL,
 credential_kind text NOT NULL,grant_id uuid,scope_kind text NOT NULL,
 action text NOT NULL,request_id text NOT NULL,decision jsonb NOT NULL CHECK(jsonb_typeof(decision)='object'),
 occurred_at timestamptz NOT NULL
);
