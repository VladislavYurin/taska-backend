SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
CREATE SEQUENCE taska.invoice_number_seq;
GRANT USAGE ON SEQUENCE taska.invoice_number_seq TO taska_billing_app;
CREATE TABLE taska.invoices (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id) ON DELETE RESTRICT,
 number text NOT NULL UNIQUE CHECK(length(number) BETWEEN 1 AND 80),
 operation_id uuid NOT NULL,
 target_key varchar(200) NOT NULL CHECK(length(target_key)>0),
 purpose text NOT NULL CHECK(purpose IN ('INITIAL','RENEWAL','PLAN_CHANGE')),
 plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id) ON DELETE RESTRICT,
 method text NOT NULL CHECK(method IN ('PSP','WIRE')),
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','OPEN','PARTIALLY_PAID','PAID','EXPIRED','VOID')),
 currency char(3) NOT NULL CHECK(currency IN ('EUR','USD')),
 currency_exponent smallint NOT NULL CHECK(currency_exponent=2),
 subtotal_minor bigint NOT NULL CHECK(subtotal_minor>=0),
 tax_minor bigint NOT NULL CHECK(tax_minor>=0),
 total_minor bigint NOT NULL CHECK(total_minor>0 AND total_minor::numeric=subtotal_minor::numeric+tax_minor::numeric),
 issued_at timestamptz NOT NULL, payable_until timestamptz NOT NULL CHECK(payable_until>issued_at),
 period_starts_at timestamptz, period_ends_at timestamptz,
 creation_deadline_snapshot timestamptz,
 terms_snapshot jsonb NOT NULL CHECK(jsonb_typeof(terms_snapshot)='object'),
 pricing_snapshot jsonb NOT NULL CHECK(jsonb_typeof(pricing_snapshot)='object'),
 seller_snapshot jsonb NOT NULL CHECK(jsonb_typeof(seller_snapshot)='object'),
 buyer_snapshot jsonb NOT NULL CHECK(jsonb_typeof(buyer_snapshot)='object'),
 snapshot text NOT NULL,
 replaces_invoice_id uuid,
 version bigint NOT NULL DEFAULT 1 CHECK(version=1),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_id),
 UNIQUE(tenant_id,target_key,purpose,plan_version_id,currency),
 FOREIGN KEY(tenant_id,replaces_invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT,
 CHECK((period_starts_at IS NULL AND period_ends_at IS NULL) OR
       (period_starts_at IS NOT NULL AND period_ends_at IS NOT NULL AND period_ends_at>period_starts_at)),
 CHECK(method<>'WIRE' OR currency='EUR')
);
CREATE TABLE taska.invoice_lines (
 tenant_id uuid NOT NULL, invoice_id uuid NOT NULL, line_no integer NOT NULL CHECK(line_no>0),
 description varchar(300) NOT NULL CHECK(length(trim(description))>0),
 quantity bigint NOT NULL CHECK(quantity>0),
 net_minor bigint NOT NULL CHECK(net_minor>=0), tax_minor bigint NOT NULL CHECK(tax_minor>=0),
 total_minor bigint NOT NULL CHECK(total_minor::numeric=net_minor::numeric+tax_minor::numeric),
 taxes_snapshot jsonb NOT NULL CHECK(jsonb_typeof(taxes_snapshot)='array'),
 snapshot text NOT NULL,
 PRIMARY KEY(tenant_id,invoice_id,line_no),
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT
);
CREATE TABLE taska.invoice_command_receipts (
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id),
 actor_id uuid NOT NULL, key varchar(128) NOT NULL,
 request_hash bytea NOT NULL, response text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,actor_id,key)
);
CREATE INDEX invoices_history_idx ON taska.invoices(tenant_id,issued_at DESC,id DESC);
CREATE INDEX invoices_expiry_idx ON taska.invoices(tenant_id,payable_until,id) WHERE state IN ('OPEN','PARTIALLY_PAID');
ALTER TABLE taska.subscription_periods ADD CONSTRAINT subscription_period_invoice_fk
 FOREIGN KEY(tenant_id,invoice_id) REFERENCES taska.invoices(tenant_id,id) ON DELETE RESTRICT;

-- This layer can issue, never settle. Financial transitions are added only by the ledger owner.
CREATE FUNCTION taska.protect_invoice() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'DRAFT' THEN RAISE EXCEPTION 'Invoice must be issued atomically' USING ERRCODE='23514'; END IF;
 ELSIF TG_OP='DELETE' THEN
  RAISE EXCEPTION 'Immutable invoice history' USING ERRCODE='23514';
 ELSIF OLD.state<>'DRAFT' OR NEW.state<>'OPEN' OR
       (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') THEN
  RAISE EXCEPTION 'Immutable invoice terms' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_invoice BEFORE INSERT OR UPDATE OR DELETE ON taska.invoices
 FOR EACH ROW EXECUTE FUNCTION taska.protect_invoice();
CREATE FUNCTION taska.protect_invoice_line() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Immutable invoice lines' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM taska.invoices WHERE tenant_id=NEW.tenant_id AND id=NEW.invoice_id AND state='DRAFT') THEN
  RAISE EXCEPTION 'Invoice is sealed' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_invoice_line BEFORE INSERT OR UPDATE OR DELETE ON taska.invoice_lines
 FOR EACH ROW EXECUTE FUNCTION taska.protect_invoice_line();
CREATE FUNCTION taska.check_invoice_complete() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM taska.invoices i JOIN LATERAL
   (SELECT count(*) n,sum(net_minor) net,sum(tax_minor) tax,sum(total_minor) total FROM taska.invoice_lines l
    WHERE l.tenant_id=i.tenant_id AND l.invoice_id=i.id) l ON true
   WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.id AND i.state='OPEN' AND l.n BETWEEN 1 AND 100
     AND i.subtotal_minor=l.net AND i.tax_minor=l.tax AND i.total_minor=l.total) THEN
  RAISE EXCEPTION 'Incomplete invoice' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER invoice_complete AFTER INSERT OR UPDATE ON taska.invoices
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION taska.check_invoice_complete();
