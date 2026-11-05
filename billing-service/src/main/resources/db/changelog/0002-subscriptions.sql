SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.subscriptions (
 tenant_id uuid PRIMARY KEY,
 selected_plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id) ON DELETE RESTRICT,
 selected_terms_snapshot jsonb NOT NULL CHECK(jsonb_typeof(selected_terms_snapshot)='object'),
 mode text NOT NULL CHECK(mode IN ('FREE','AWAITING_PAYMENT','PAID')),
 current_period_id uuid,
 renewal_enabled boolean NOT NULL DEFAULT false,
 renewal_cancelled boolean NOT NULL DEFAULT false,
 autopay_state text NOT NULL DEFAULT 'DISABLED' CHECK(autopay_state IN ('DISABLED','CONSENTED','SUSPENDED')),
 next_plan_version_id uuid REFERENCES taska.catalog_plan_versions(id) ON DELETE RESTRICT,
 next_effective_at timestamptz,
 anchor_day smallint CHECK(anchor_day BETWEEN 1 AND 31),
 anchor_time_utc time,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((anchor_day IS NULL)=(anchor_time_utc IS NULL))
);
CREATE TABLE taska.subscription_periods (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id) ON DELETE RESTRICT,
 invoice_id uuid NOT NULL,
 source_financial_fact_id uuid NOT NULL,
 plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id) ON DELETE RESTRICT,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 anchor_day smallint NOT NULL CHECK(anchor_day BETWEEN 1 AND 31), anchor_time_utc time NOT NULL,
 status text NOT NULL DEFAULT 'GRANTED' CHECK(status IN ('GRANTED','REVOKED','SUPERSEDED')),
 activation_operation_id uuid NOT NULL,
 terms_snapshot jsonb NOT NULL CHECK(jsonb_typeof(terms_snapshot)='object'),
 confirmed_at timestamptz NOT NULL,
 payment_source text NOT NULL CHECK(payment_source IN ('PSP','WIRE')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,invoice_id), UNIQUE(tenant_id,activation_operation_id),
 CHECK(ends_at>starts_at)
);
-- TAS-475 adds (tenant_id,invoice_id) -> invoices(tenant_id,id) after the real invoices table exists.
-- Settlement remains fail-closed until authoritative invoice/payment evidence is supplied.
ALTER TABLE taska.subscriptions ADD CONSTRAINT subscription_current_period_fk
 FOREIGN KEY(tenant_id,current_period_id) REFERENCES taska.subscription_periods(tenant_id,id) ON DELETE RESTRICT;
CREATE INDEX subscription_periods_window_idx ON taska.subscription_periods(tenant_id,starts_at DESC,id DESC) WHERE status='GRANTED';
CREATE INDEX subscription_periods_history_idx ON taska.subscription_periods(tenant_id,created_at DESC,id DESC);
CREATE INDEX subscription_periods_source_idx ON taska.subscription_periods(tenant_id,source_financial_fact_id,invoice_id);
CREATE FUNCTION taska.protect_subscription_period() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable period history' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-'status') IS DISTINCT FROM (to_jsonb(OLD)-'status') OR
    (NEW.status IS DISTINCT FROM OLD.status AND (OLD.status<>'GRANTED' OR NEW.status NOT IN ('REVOKED','SUPERSEDED'))) THEN
   RAISE EXCEPTION 'Immutable period terms or invalid transition' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_subscription_period BEFORE UPDATE OR DELETE ON taska.subscription_periods
 FOR EACH ROW EXECUTE FUNCTION taska.protect_subscription_period();
CREATE TABLE taska.subscription_command_receipts (
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id) DEFERRABLE INITIALLY DEFERRED,
 operation_code text NOT NULL, key varchar(128) NOT NULL,
 request_hash bytea NOT NULL, response text NOT NULL,
 issuer_service text NOT NULL, actor_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,operation_code,key)
);
