SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
-- Authenticated transport data is global until a trusted local payment mapping is known.
CREATE TABLE taska.provider_inbox (
 id uuid PRIMARY KEY,
 provider text NOT NULL CHECK(length(provider) BETWEEN 1 AND 80),
 provider_account_id text NOT NULL CHECK(length(provider_account_id) BETWEEN 1 AND 200),
 environment text NOT NULL CHECK(environment IN ('TEST','LIVE')),
 event_id text NOT NULL CHECK(length(event_id) BETWEEN 1 AND 200),
 payload_digest text NOT NULL CHECK(payload_digest ~ '^[a-f0-9]{64}$'),
 payload_key_id text,
 payload_nonce bytea,
 payload_ciphertext bytea,
 observation jsonb NOT NULL,
 signature_verified boolean NOT NULL CHECK(signature_verified),
 state text NOT NULL CHECK(state IN ('RECEIVED','VERIFIED','APPLIED','IGNORED','QUARANTINED')),
 reason_code text NOT NULL,
 assigned_tenant_id uuid,
 evidence_id uuid,
 received_at timestamptz NOT NULL,
 next_attempt_at timestamptz NOT NULL,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 processed_at timestamptz,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 UNIQUE(provider,provider_account_id,environment,event_id),
 CHECK((payload_key_id IS NULL)=(payload_nonce IS NULL) AND (payload_nonce IS NULL)=(payload_ciphertext IS NULL))
);
CREATE INDEX provider_inbox_work_idx ON taska.provider_inbox(state,next_attempt_at,received_at,id)
 WHERE state IN ('RECEIVED','VERIFIED');
CREATE TABLE taska.provider_pay_routes (
 provider text NOT NULL, provider_account_id text NOT NULL, environment text NOT NULL,
 provider_payment_id text NOT NULL CHECK(length(provider_payment_id) BETWEEN 1 AND 200),
 tenant_id uuid NOT NULL, invoice_id uuid NOT NULL, operation_id uuid NOT NULL,
 merchant_profile_version text NOT NULL CHECK(length(merchant_profile_version) BETWEEN 1 AND 100),
 created_at timestamptz NOT NULL,
 PRIMARY KEY(provider,provider_account_id,environment,provider_payment_id),
 CONSTRAINT provider_routes_proof_identity UNIQUE(provider,provider_account_id,environment,provider_payment_id,tenant_id,invoice_id,operation_id,merchant_profile_version),
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT,
 CHECK(environment IN ('TEST','LIVE'))
);
CREATE TABLE taska.provider_payment_proofs (
 id uuid PRIMARY KEY,
 provider text NOT NULL, provider_account_id text NOT NULL, environment text NOT NULL,
 provider_payment_id text NOT NULL,
 tenant_id uuid NOT NULL, invoice_id uuid NOT NULL, operation_id uuid NOT NULL,
 merchant_profile_version text NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor>0), currency char(3) NOT NULL CHECK(currency IN ('EUR','USD')),
 occurred_at timestamptz NOT NULL, observed_at timestamptz NOT NULL,
 evidence_digest text NOT NULL CHECK(evidence_digest ~ '^[a-f0-9]{64}$'),
 UNIQUE(provider,provider_account_id,environment,provider_payment_id),
 UNIQUE(tenant_id,id),
 CONSTRAINT provider_proof_binding FOREIGN KEY(provider,provider_account_id,environment,provider_payment_id,tenant_id,invoice_id,operation_id,merchant_profile_version)
 REFERENCES taska.provider_pay_routes(provider,provider_account_id,environment,provider_payment_id,tenant_id,invoice_id,operation_id,merchant_profile_version) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT
);
ALTER TABLE taska.provider_inbox ADD CONSTRAINT provider_inbox_evidence_tenant
 FOREIGN KEY(assigned_tenant_id,evidence_id) REFERENCES taska.provider_payment_proofs(tenant_id,id) ON DELETE RESTRICT;
ALTER TABLE taska.provider_inbox ADD CONSTRAINT provider_inbox_proof_state
 CHECK(state NOT IN ('VERIFIED','APPLIED') OR (assigned_tenant_id IS NOT NULL AND evidence_id IS NOT NULL));
CREATE FUNCTION taska.protect_provider_source() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable provider financial source' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_provider_route BEFORE UPDATE OR DELETE ON taska.provider_pay_routes
 FOR EACH ROW EXECUTE FUNCTION taska.protect_provider_source();
CREATE TRIGGER immutable_provider_proof BEFORE UPDATE OR DELETE ON taska.provider_payment_proofs
 FOR EACH ROW EXECUTE FUNCTION taska.protect_provider_source();
CREATE TABLE taska.provider_ingress_limits (
 provider text NOT NULL, provider_account_id text NOT NULL, environment text NOT NULL,
 bucket timestamptz NOT NULL, attempts integer NOT NULL CHECK(attempts>0),
 PRIMARY KEY(provider,provider_account_id,environment,bucket)
);
-- These tables are the explicit GLOBAL_FINANCIAL exception, not tenant content tables.
-- They are reachable only through the private authenticated adapter/worker service.
GRANT SELECT,INSERT,UPDATE ON taska.provider_inbox TO taska_billing_app;
GRANT SELECT,INSERT ON taska.provider_pay_routes,taska.provider_payment_proofs TO taska_billing_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON taska.provider_ingress_limits TO taska_billing_app;
CREATE FUNCTION taska.protect_provider_inbox() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP<>'UPDATE' OR
    (to_jsonb(NEW)-ARRAY['state','reason_code','assigned_tenant_id','evidence_id','processed_at','version','attempts','next_attempt_at','payload_key_id','payload_nonce','payload_ciphertext'])
     IS DISTINCT FROM
    (to_jsonb(OLD)-ARRAY['state','reason_code','assigned_tenant_id','evidence_id','processed_at','version','attempts','next_attempt_at','payload_key_id','payload_nonce','payload_ciphertext']) THEN
   RAISE EXCEPTION 'Immutable authenticated webhook' USING ERRCODE='23514';
 END IF;
 IF ROW(NEW.payload_key_id,NEW.payload_nonce,NEW.payload_ciphertext) IS DISTINCT FROM ROW(OLD.payload_key_id,OLD.payload_nonce,OLD.payload_ciphertext)
    AND NOT (NEW.payload_key_id IS NULL AND NEW.payload_nonce IS NULL AND NEW.payload_ciphertext IS NULL
      AND OLD.processed_at IS NOT NULL AND OLD.state IN ('APPLIED','IGNORED')) THEN
   RAISE EXCEPTION 'Immutable private webhook payload' USING ERRCODE='23514';
 END IF;
 IF NEW.attempts<OLD.attempts OR NEW.next_attempt_at<OLD.next_attempt_at
    OR (OLD.assigned_tenant_id IS NOT NULL AND NEW.assigned_tenant_id IS DISTINCT FROM OLD.assigned_tenant_id)
    OR (OLD.evidence_id IS NOT NULL AND NEW.evidence_id IS DISTINCT FROM OLD.evidence_id)
    OR (OLD.processed_at IS NOT NULL AND NEW.processed_at IS DISTINCT FROM OLD.processed_at)
    OR NOT (NEW.state=OLD.state OR (OLD.state='RECEIVED' AND NEW.state IN ('VERIFIED','QUARANTINED')) OR (OLD.state='VERIFIED' AND NEW.state='APPLIED')) THEN
   RAISE EXCEPTION 'Monotonic webhook state required' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_provider_inbox BEFORE UPDATE OR DELETE ON taska.provider_inbox
 FOR EACH ROW EXECUTE FUNCTION taska.protect_provider_inbox();
