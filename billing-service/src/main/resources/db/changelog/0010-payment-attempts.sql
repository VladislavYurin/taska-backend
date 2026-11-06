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
  request_hash bytea NOT NULL,
  state text NOT NULL CHECK (state IN ('NEW','PROCESSING','UNKNOWN','SUCCEEDED','FAILED')),
  fence bigint NOT NULL CHECK (fence > 0),
  result jsonb,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
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
  mandate_id uuid,
  provider text NOT NULL,
  provider_idempotency_key text NOT NULL,
  provider_payment_id text,
  invoice_snapshot jsonb NOT NULL,
  merchant_account text NOT NULL,
  provider_environment text NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
  state text NOT NULL CHECK (state IN ('ACCEPTED','SUBMITTED','PENDING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
  accepted_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (provider, provider_idempotency_key),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT
);
CREATE INDEX payment_attempts_invoice_state_idx
  ON taska.payment_attempts(tenant_id,invoice_id,state);
