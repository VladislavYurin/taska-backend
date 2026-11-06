SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.payment_facts (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id),
 provider varchar(80) NOT NULL, provider_account_id varchar(120) NOT NULL,
 environment text NOT NULL CHECK(environment IN ('SANDBOX','LIVE')),
 external_fact_id varchar(200) NOT NULL,
 fact_kind text NOT NULL CHECK(fact_kind IN ('PAYMENT','REFUND','CHARGEBACK','CHARGEBACK_REVERSAL')),
 parent_fact_id uuid, root_payment_id uuid NOT NULL, allocation_id uuid,
 amount_minor bigint NOT NULL CHECK(amount_minor>0),
 currency char(3) NOT NULL CHECK(currency IN ('EUR','USD')), currency_exponent smallint NOT NULL CHECK(currency_exponent=2),
 source_method text NOT NULL CHECK(source_method IN ('PSP','WIRE')),
 occurred_precision text NOT NULL CHECK(occurred_precision IN ('EXACT','DAY')),
 occurred_at timestamptz, occurred_not_before timestamptz, occurred_before timestamptz,
 observed_at timestamptz NOT NULL, verified_at timestamptz NOT NULL, verified_by uuid,
 payer_ref varchar(200), evidence_ref varchar(200) NOT NULL, evidence_digest bytea NOT NULL CHECK(octet_length(evidence_digest)=32),
 request_hash bytea NOT NULL CHECK(octet_length(request_hash)=32),
 schema_version integer NOT NULL DEFAULT 1 CHECK(schema_version=1), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(provider,provider_account_id,environment,external_fact_id),
 FOREIGN KEY(tenant_id,parent_fact_id) REFERENCES taska.payment_facts(tenant_id,id),
 FOREIGN KEY(tenant_id,root_payment_id) REFERENCES taska.payment_facts(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
 CHECK((occurred_precision='EXACT' AND occurred_at IS NOT NULL AND occurred_not_before IS NULL AND occurred_before IS NULL)
    OR (occurred_precision='DAY' AND occurred_at IS NULL AND occurred_not_before IS NOT NULL AND occurred_before IS NOT NULL AND occurred_before>occurred_not_before)),
 CHECK(source_method<>'WIRE' OR (currency='EUR' AND payer_ref IS NOT NULL AND length(payer_ref)>0 AND verified_by IS NOT NULL)),
 CHECK(length(provider)>0 AND length(provider_account_id)>0 AND length(external_fact_id)>0 AND length(evidence_ref)>0)
);
CREATE TABLE taska.invoice_payment_matches (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, payment_fact_id uuid NOT NULL, invoice_id uuid NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor>0), operation_id uuid NOT NULL, matched_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,payment_fact_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,payment_fact_id) REFERENCES taska.payment_facts(tenant_id,id),
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id)
);
CREATE TABLE taska.payment_allocations (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, payment_fact_id uuid NOT NULL, invoice_id uuid NOT NULL,
 match_id uuid NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0), operation_id uuid NOT NULL,
 eligibility_evidence jsonb NOT NULL CHECK(jsonb_typeof(eligibility_evidence)='object'), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,match_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,payment_fact_id) REFERENCES taska.payment_facts(tenant_id,id),
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id),
 FOREIGN KEY(tenant_id,match_id) REFERENCES taska.invoice_payment_matches(tenant_id,id)
);
ALTER TABLE taska.payment_facts ADD CONSTRAINT financial_fact_allocation_fk
 FOREIGN KEY(tenant_id,allocation_id) REFERENCES taska.payment_allocations(tenant_id,id);
CREATE TABLE taska.financial_command_receipts (
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id), action varchar(80) NOT NULL, key varchar(128) NOT NULL,
 request_hash bytea NOT NULL, result_ids jsonb NOT NULL CHECK(jsonb_typeof(result_ids)='array'), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,action,key)
);
CREATE INDEX payment_facts_root_idx ON taska.payment_facts(tenant_id,root_payment_id);
CREATE INDEX payment_facts_parent_idx ON taska.payment_facts(tenant_id,parent_fact_id);
CREATE INDEX payment_facts_history_idx ON taska.payment_facts(tenant_id,created_at DESC,id DESC);
CREATE INDEX payment_allocations_invoice_idx ON taska.payment_allocations(tenant_id,invoice_id);
CREATE INDEX payment_allocations_source_idx ON taska.payment_allocations(tenant_id,payment_fact_id);
CREATE INDEX invoice_payment_matches_invoice_idx ON taska.invoice_payment_matches(tenant_id,invoice_id);

-- Invoker rights are essential: view reads must retain the runtime tenant's FORCE RLS.
-- Matches remain unallocated principal until final allocation; do not subtract them twice.
CREATE VIEW taska.financial_balances WITH(security_invoker=true) AS
 SELECT p.tenant_id,p.id,p.currency,p.amount_minor AS received,
   coalesce(a.allocated,0)-coalesce(r.allocated_returns,0) AS net_allocated,
   p.amount_minor::numeric-coalesce(a.allocated,0)-coalesce(r.unallocated_returns,0) AS unallocated_remaining,
   coalesce(r.allocated_returns,0)+coalesce(r.unallocated_returns,0) AS confirmed_net_returned,
   greatest(least(coalesce(m.matched,0)-coalesce(a.allocated,0),
     p.amount_minor::numeric-coalesce(a.allocated,0)-coalesce(r.unallocated_returns,0)),0) AS matched_unallocated
 FROM taska.payment_facts p
 LEFT JOIN LATERAL(SELECT sum(amount_minor) allocated FROM taska.payment_allocations WHERE tenant_id=p.tenant_id AND payment_fact_id=p.id) a ON true
 LEFT JOIN LATERAL(SELECT
   sum(CASE WHEN fact_kind='CHARGEBACK_REVERSAL' THEN -amount_minor::numeric ELSE amount_minor::numeric END) FILTER(WHERE allocation_id IS NOT NULL) allocated_returns,
   sum(CASE WHEN fact_kind='CHARGEBACK_REVERSAL' THEN -amount_minor::numeric ELSE amount_minor::numeric END) FILTER(WHERE allocation_id IS NULL) unallocated_returns
   FROM taska.payment_facts WHERE tenant_id=p.tenant_id AND root_payment_id=p.id AND id<>p.id) r ON true
 LEFT JOIN LATERAL(SELECT amount_minor::numeric matched FROM taska.invoice_payment_matches WHERE tenant_id=p.tenant_id AND payment_fact_id=p.id) m ON true
 WHERE p.fact_kind='PAYMENT';
GRANT SELECT ON taska.financial_balances TO taska_billing_app;

CREATE FUNCTION taska.guard_financial_fact() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE parent taska.payment_facts; root taska.payment_facts; capacity numeric; restored numeric;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Immutable financial fact' USING ERRCODE='23514'; END IF;
 IF NEW.fact_kind='PAYMENT' THEN
  IF NEW.parent_fact_id IS NOT NULL OR NEW.allocation_id IS NOT NULL OR NEW.root_payment_id<>NEW.id THEN
   RAISE EXCEPTION 'Invalid payment origin' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO parent FROM taska.payment_facts WHERE tenant_id=NEW.tenant_id AND id=NEW.parent_fact_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing financial parent' USING ERRCODE='23514'; END IF;
 SELECT * INTO root FROM taska.payment_facts WHERE tenant_id=NEW.tenant_id AND id=parent.root_payment_id FOR UPDATE;
 IF NOT FOUND OR root.fact_kind<>'PAYMENT' OR NEW.root_payment_id<>root.id OR
    (NEW.provider,NEW.provider_account_id,NEW.environment,NEW.currency,NEW.currency_exponent,NEW.source_method,NEW.payer_ref)
    IS DISTINCT FROM (root.provider,root.provider_account_id,root.environment,root.currency,root.currency_exponent,root.source_method,root.payer_ref) THEN
  RAISE EXCEPTION 'Financial source mismatch' USING ERRCODE='23514';
 END IF;
 IF NEW.fact_kind='CHARGEBACK_REVERSAL' THEN
  IF parent.fact_kind<>'CHARGEBACK' OR NEW.allocation_id IS DISTINCT FROM parent.allocation_id THEN
   RAISE EXCEPTION 'Invalid reversal parent' USING ERRCODE='23514';
  END IF;
  SELECT coalesce(sum(amount_minor),0) INTO restored FROM taska.payment_facts
    WHERE tenant_id=NEW.tenant_id AND parent_fact_id=parent.id AND fact_kind='CHARGEBACK_REVERSAL';
  capacity=parent.amount_minor::numeric-restored;
 ELSE
  IF parent.fact_kind<>'PAYMENT' THEN RAISE EXCEPTION 'Return must reference original payment' USING ERRCODE='23514'; END IF;
  IF NEW.allocation_id IS NULL THEN
   SELECT unallocated_remaining INTO capacity FROM taska.financial_balances WHERE tenant_id=NEW.tenant_id AND id=root.id;
  ELSE
   SELECT a.amount_minor::numeric-coalesce((SELECT sum(CASE WHEN f.fact_kind='CHARGEBACK_REVERSAL' THEN -f.amount_minor::numeric ELSE f.amount_minor::numeric END)
     FROM taska.payment_facts f WHERE f.tenant_id=a.tenant_id AND f.allocation_id=a.id),0)
     INTO capacity FROM taska.payment_allocations a WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.allocation_id AND a.payment_fact_id=root.id;
  END IF;
 END IF;
 IF capacity IS NULL OR NEW.amount_minor>capacity THEN RAISE EXCEPTION 'Return exceeds remaining principal' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_financial_fact BEFORE INSERT OR UPDATE OR DELETE ON taska.payment_facts
 FOR EACH ROW EXECUTE FUNCTION taska.guard_financial_fact();
CREATE FUNCTION taska.guard_financial_link() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE invoice taska.invoices; source taska.payment_facts; matched taska.invoice_payment_matches; used numeric; available numeric;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Immutable financial link' USING ERRCODE='23514'; END IF;
 -- Every linking operation locks invoice first, then payment roots in UUID order.
 SELECT * INTO invoice FROM taska.invoices WHERE tenant_id=NEW.tenant_id AND id=NEW.invoice_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing invoice' USING ERRCODE='23514'; END IF;
 SELECT * INTO source FROM taska.payment_facts WHERE tenant_id=NEW.tenant_id AND id=NEW.payment_fact_id FOR UPDATE;
 IF NOT FOUND OR source.fact_kind<>'PAYMENT' OR source.currency<>invoice.currency OR source.currency_exponent<>invoice.currency_exponent THEN
  RAISE EXCEPTION 'Financial link mismatch' USING ERRCODE='23514';
 END IF;
 SELECT unallocated_remaining INTO available FROM taska.financial_balances WHERE tenant_id=NEW.tenant_id AND id=source.id;
 IF TG_TABLE_NAME='invoice_payment_matches' THEN
  SELECT coalesce(sum(amount_minor),0) INTO used FROM taska.invoice_payment_matches WHERE tenant_id=NEW.tenant_id AND invoice_id=invoice.id;
  IF invoice.state NOT IN ('OPEN','PARTIALLY_PAID') OR used+NEW.amount_minor>invoice.total_minor THEN
   RAISE EXCEPTION 'Match exceeds invoice' USING ERRCODE='23514';
  END IF;
 ELSE
  SELECT * INTO matched FROM taska.invoice_payment_matches WHERE tenant_id=NEW.tenant_id AND id=NEW.match_id;
  IF NOT FOUND OR (matched.payment_fact_id,matched.invoice_id,matched.amount_minor) IS DISTINCT FROM (NEW.payment_fact_id,NEW.invoice_id,NEW.amount_minor) THEN
   RAISE EXCEPTION 'Allocation must equal original match' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.amount_minor>available THEN RAISE EXCEPTION 'Link exceeds available principal' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment_match BEFORE INSERT OR UPDATE OR DELETE ON taska.invoice_payment_matches
 FOR EACH ROW EXECUTE FUNCTION taska.guard_financial_link();
CREATE TRIGGER immutable_payment_allocation BEFORE INSERT OR UPDATE OR DELETE ON taska.payment_allocations
 FOR EACH ROW EXECUTE FUNCTION taska.guard_financial_link();
CREATE FUNCTION taska.check_full_invoice_allocation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM taska.invoices i WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.invoice_id AND
    i.total_minor=(SELECT sum(amount_minor) FROM taska.payment_allocations a WHERE a.tenant_id=i.tenant_id AND a.invoice_id=i.id)) THEN
  RAISE EXCEPTION 'Partial final allocation is forbidden' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER full_invoice_allocation AFTER INSERT ON taska.payment_allocations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION taska.check_full_invoice_allocation();
