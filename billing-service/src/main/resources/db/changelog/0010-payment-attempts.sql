SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;

CREATE TABLE taska.billing_operations (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  kind text NOT NULL,
  actor_id uuid,
  channel text NOT NULL,
  grant_id uuid,
  request_key varchar(128) NOT NULL,
  request_hash bytea NOT NULL CHECK(octet_length(request_hash)=32),
  state text NOT NULL CHECK (state IN ('NEW','PROCESSING','UNKNOWN','SUCCEEDED','FAILED')),
  fence bigint NOT NULL CHECK (fence > 0),
  result jsonb,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id, kind, request_key)
);
CREATE INDEX billing_operations_pending_idx
  ON taska.billing_operations(tenant_id,state,updated_at,id)
  WHERE state IN ('NEW','PROCESSING','UNKNOWN');

CREATE TABLE taska.payment_methods (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  owner_id uuid NOT NULL,
  provider text NOT NULL,
  provider_customer_ref text NOT NULL,
  provider_method_ref text NOT NULL,
  status text NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
  created_at timestamptz NOT NULL,
  revoked_at timestamptz,
  UNIQUE (tenant_id,id)
);

CREATE TABLE taska.payment_attempts (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  invoice_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  amount_minor bigint NOT NULL CHECK(amount_minor>0),
  currency char(3) NOT NULL CHECK(currency IN ('EUR','USD')),
  mandate_id uuid,
  provider text NOT NULL,
  provider_idempotency_key text NOT NULL,
  provider_payment_id text,
  request_snapshot jsonb NOT NULL CHECK(jsonb_typeof(request_snapshot)='object'),
  merchant_profile_version text NOT NULL,
  invoice_snapshot jsonb NOT NULL CHECK(jsonb_typeof(invoice_snapshot)='object'),
  merchant_account text NOT NULL,
  provider_environment text NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
  state text NOT NULL CHECK (state IN ('ACCEPTED','SUBMITTED','PENDING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
  accepted_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (provider, provider_idempotency_key),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id,operation_id) REFERENCES taska.billing_operations(tenant_id,id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT
);
CREATE INDEX payment_attempts_invoice_state_idx
  ON taska.payment_attempts(tenant_id,invoice_id,state);

CREATE UNIQUE INDEX payment_attempts_provider_object_uniq ON taska.payment_attempts
  (provider,merchant_account,provider_environment,provider_payment_id) WHERE provider_payment_id IS NOT NULL;
CREATE UNIQUE INDEX payment_attempts_invoice_active_uniq ON taska.payment_attempts(tenant_id,invoice_id)
  WHERE state IN ('ACCEPTED','SUBMITTED','PENDING','UNKNOWN','SUCCEEDED');

CREATE FUNCTION taska.protect_payment_attempt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.invoice_id,NEW.operation_id,NEW.provider,NEW.provider_idempotency_key,
      NEW.merchant_account,NEW.provider_environment,NEW.merchant_profile_version,NEW.request_snapshot,NEW.invoice_snapshot,NEW.amount_minor,NEW.currency,NEW.accepted_at)
     IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.invoice_id,OLD.operation_id,OLD.provider,OLD.provider_idempotency_key,
      OLD.merchant_account,OLD.provider_environment,OLD.merchant_profile_version,OLD.request_snapshot,OLD.invoice_snapshot,OLD.amount_minor,OLD.currency,OLD.accepted_at)
     OR (OLD.provider_payment_id IS NOT NULL AND NEW.provider_payment_id IS DISTINCT FROM OLD.provider_payment_id)
     OR OLD.state IN ('SUCCEEDED','FAILED','CANCELLED')
     OR (OLD.state<>'ACCEPTED' AND NEW.state IN ('ACCEPTED','SUBMITTED'))
     OR (OLD.state='ACCEPTED' AND NEW.state<>'SUBMITTED')
     OR NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION 'Immutable payment attempt identity or terminal state' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment_attempt BEFORE UPDATE ON taska.payment_attempts
  FOR EACH ROW EXECUTE FUNCTION taska.protect_payment_attempt();

CREATE FUNCTION taska.protect_payment_operation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.kind,NEW.actor_id,NEW.channel,NEW.grant_id,NEW.request_key,NEW.request_hash,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.kind,OLD.actor_id,OLD.channel,OLD.grant_id,OLD.request_key,OLD.request_hash,OLD.created_at)
     OR OLD.state IN ('SUCCEEDED','FAILED') OR NEW.fence<>OLD.fence+1 THEN
    RAISE EXCEPTION 'Immutable payment operation identity or terminal result' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment_operation BEFORE UPDATE ON taska.billing_operations
  FOR EACH ROW EXECUTE FUNCTION taska.protect_payment_operation();
