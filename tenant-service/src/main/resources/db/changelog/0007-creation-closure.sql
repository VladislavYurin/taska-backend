SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
ALTER TABLE taska.tenants ADD COLUMN creation_closure_reason text
 CHECK(creation_closure_reason IN ('AUTO_EXPIRED','OWNER_CANCELLED'));
ALTER TABLE taska.tenants ADD COLUMN creation_closed_at timestamptz;
ALTER TABLE taska.tenants ADD COLUMN first_payment_decision_id uuid;
ALTER TABLE taska.tenants ADD COLUMN first_payment_invoice_id uuid;
ALTER TABLE taska.tenants ADD COLUMN first_payment_coverage_id uuid;
ALTER TABLE taska.tenants ADD CONSTRAINT creation_closure_shape
 CHECK((creation_closure_reason IS NULL)=(creation_closed_at IS NULL));
CREATE FUNCTION taska.protect_first_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.first_payment_fact_id IS NOT NULL AND
 (NEW.first_payment_fact_id,NEW.first_payment_decision_id,NEW.first_payment_invoice_id,NEW.first_payment_coverage_id)
 IS DISTINCT FROM
 (OLD.first_payment_fact_id,OLD.first_payment_decision_id,OLD.first_payment_invoice_id,OLD.first_payment_coverage_id)
 THEN RAISE EXCEPTION 'Immutable first payment' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_first_payment BEFORE UPDATE ON taska.tenants FOR EACH ROW EXECUTE FUNCTION taska.protect_first_payment();
CREATE TABLE taska.tenant_creation_receipts (
 tenant_id uuid NOT NULL REFERENCES taska.tenants(id), actor_scope text NOT NULL,
 action text NOT NULL CHECK(action IN ('cancelTenantCreation','expireTenantCreation','applyTenantInitialPaymentDecision')),
 key uuid NOT NULL, request_hash bytea NOT NULL CHECK(octet_length(request_hash)=32),
 result text NOT NULL, created_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,actor_scope,action,key)
);
CREATE FUNCTION taska.immutable_creation_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Immutable creation receipt' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_creation_receipt BEFORE UPDATE OR DELETE ON taska.tenant_creation_receipts
 FOR EACH ROW EXECUTE FUNCTION taska.immutable_creation_receipt();
ALTER TABLE taska.tenant_provisioning_dispatch ADD COLUMN creation_deadline_at timestamptz;
ALTER TABLE taska.tenant_provisioning_dispatch ADD COLUMN creation_expiry_completed_at timestamptz;
CREATE INDEX tenant_creation_expiry_due ON taska.tenant_provisioning_dispatch(creation_deadline_at,tenant_id)
 WHERE creation_deadline_at IS NOT NULL AND creation_expiry_completed_at IS NULL;
GRANT UPDATE(creation_expiry_completed_at) ON taska.tenant_provisioning_dispatch TO taska_tenant_app;
-- The owner-only bootstrap read and FORCE restoration are in this one transactional changeset.
ALTER TABLE taska.tenants NO FORCE ROW LEVEL SECURITY;
UPDATE taska.tenant_provisioning_dispatch d SET creation_deadline_at=t.creation_deadline_at,
 creation_expiry_completed_at=CASE WHEN t.first_payment_fact_id IS NOT NULL OR t.lifecycle<>'AWAITING_PAYMENT' THEN t.updated_at END
 FROM taska.tenants t WHERE t.id=d.tenant_id AND t.creation_kind='PAID';
UPDATE taska.tenants SET creation_closure_reason=CASE lifecycle WHEN 'AUTO_CLOSED' THEN 'AUTO_EXPIRED' ELSE 'OWNER_CANCELLED' END,
 creation_closed_at=updated_at WHERE lifecycle IN ('AUTO_CLOSED','OWNER_CANCELLED');
ALTER TABLE taska.tenants FORCE ROW LEVEL SECURITY;
