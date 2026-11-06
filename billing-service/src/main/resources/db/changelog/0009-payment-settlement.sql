SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
ALTER TABLE taska.invoices ADD COLUMN selection_generation bigint NOT NULL DEFAULT 0 CHECK(selection_generation>=0);
-- DDL holds an exclusive table lock until this changeset commits; application writes cannot interleave.
-- The immutable intent provides the exact generation of an already issued plan-change invoice.
ALTER TABLE taska.invoices NO FORCE ROW LEVEL SECURITY;
ALTER TABLE taska.subscription_plan_changes NO FORCE ROW LEVEL SECURITY;
ALTER TABLE taska.invoices DISABLE TRIGGER immutable_invoice;
ALTER TABLE taska.invoices DISABLE TRIGGER invoice_complete;
UPDATE taska.invoices i SET selection_generation=p.selection_generation
 FROM taska.subscription_plan_changes p
 WHERE i.tenant_id=p.tenant_id AND i.operation_id=p.id AND i.purpose='PLAN_CHANGE';
ALTER TABLE taska.invoices ENABLE TRIGGER immutable_invoice;
ALTER TABLE taska.invoices ENABLE TRIGGER invoice_complete;
ALTER TABLE taska.invoices FORCE ROW LEVEL SECURITY;
ALTER TABLE taska.subscription_plan_changes FORCE ROW LEVEL SECURITY;

CREATE TABLE taska.payment_settlements (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,invoice_id uuid NOT NULL,financial_fact_id uuid NOT NULL,
 coverage_id uuid,creation_operation_id uuid,occurred_at timestamptz NOT NULL,
 invoice_deadline timestamptz NOT NULL,plan_version_id uuid NOT NULL,
 outcome text NOT NULL CHECK(outcome IN ('APPLIED','REVIEW_REQUIRED')),reason_code varchar(80) NOT NULL,
 response text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,financial_fact_id),
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id),
 FOREIGN KEY(tenant_id,financial_fact_id) REFERENCES taska.payment_facts(tenant_id,id),
 FOREIGN KEY(tenant_id,coverage_id) REFERENCES taska.subscription_periods(tenant_id,id),
 CHECK((outcome='APPLIED')=(coverage_id IS NOT NULL))
);
CREATE UNIQUE INDEX one_settlement_per_invoice ON taska.payment_settlements(tenant_id,invoice_id) WHERE outcome='APPLIED';
CREATE FUNCTION taska.protect_settlement() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable settlement decision' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_settlement BEFORE UPDATE OR DELETE ON taska.payment_settlements
 FOR EACH ROW EXECUTE FUNCTION taska.protect_settlement();
CREATE FUNCTION taska.freeze_invoice_selection() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 SELECT selection_generation INTO STRICT NEW.selection_generation FROM taska.subscriptions WHERE tenant_id=NEW.tenant_id;
 RETURN NEW;
END $$;
CREATE TRIGGER invoice_selection BEFORE INSERT ON taska.invoices FOR EACH ROW EXECUTE FUNCTION taska.freeze_invoice_selection();
CREATE OR REPLACE FUNCTION taska.protect_invoice() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'DRAFT' THEN RAISE EXCEPTION 'Invoice must be issued atomically' USING ERRCODE='23514'; END IF;
 ELSIF TG_OP='DELETE' THEN
  RAISE EXCEPTION 'Immutable invoice history' USING ERRCODE='23514';
 ELSIF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') OR
       NOT ((OLD.state='DRAFT' AND NEW.state='OPEN') OR (OLD.state IN ('OPEN','PARTIALLY_PAID') AND NEW.state='PAID')) THEN
  RAISE EXCEPTION 'Immutable invoice terms or invalid settlement' USING ERRCODE='23514';
 ELSIF NEW.state='PAID' AND NEW.total_minor<>(SELECT coalesce(sum(amount_minor),0) FROM taska.payment_allocations WHERE tenant_id=NEW.tenant_id AND invoice_id=NEW.id) THEN
  RAISE EXCEPTION 'Full allocation required' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION taska.check_invoice_complete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM taska.invoices i JOIN LATERAL
   (SELECT count(*) n,sum(net_minor) net,sum(tax_minor) tax,sum(total_minor) total FROM taska.invoice_lines l
    WHERE l.tenant_id=i.tenant_id AND l.invoice_id=i.id) l ON true
   WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.id AND i.state IN ('OPEN','PARTIALLY_PAID','PAID','EXPIRED','VOID') AND l.n BETWEEN 1 AND 100
     AND i.subtotal_minor=l.net AND i.tax_minor=l.tax AND i.total_minor=l.total) THEN
  RAISE EXCEPTION 'Incomplete invoice' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;

CREATE FUNCTION taska.check_paid_settlement() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.state='PAID' AND NOT EXISTS(SELECT 1 FROM taska.payment_settlements d JOIN taska.subscription_periods p
   ON p.tenant_id=d.tenant_id AND p.id=d.coverage_id AND p.invoice_id=d.invoice_id AND p.source_financial_fact_id=d.financial_fact_id
   WHERE d.tenant_id=NEW.tenant_id AND d.invoice_id=NEW.id AND d.outcome='APPLIED') THEN
  RAISE EXCEPTION 'Paid invoice requires atomic settlement and coverage' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER paid_settlement AFTER INSERT OR UPDATE ON taska.invoices
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION taska.check_paid_settlement();
