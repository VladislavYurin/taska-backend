SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
-- GLOBAL_FINANCIAL work directory, containing only immutable owner pointers/correlation.
-- No lifecycle predicate: financial recovery survives company closure/deletion.
CREATE TABLE taska.payment_reconciliation (
 tenant_id uuid NOT NULL, attempt_id uuid PRIMARY KEY, correlation_id uuid NOT NULL,
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DONE','MANUAL')),
 reason_code text NOT NULL DEFAULT 'AWAITING_RECONCILIATION',
 created_at timestamptz NOT NULL, next_attempt_at timestamptz NOT NULL,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 lease_token uuid, lease_until timestamptz,
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES taska.payment_attempts(tenant_id,id) ON DELETE RESTRICT,
 CHECK((lease_token IS NULL)=(lease_until IS NULL))
);
CREATE INDEX payment_reconciliation_due ON taska.payment_reconciliation(next_attempt_at,attempt_id) WHERE state='PENDING';
CREATE INDEX payment_reconciliation_open_summary ON taska.payment_reconciliation(state,created_at) WHERE state<>'DONE';
CREATE FUNCTION taska.enqueue_payment_reconciliation() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO taska.payment_reconciliation(tenant_id,attempt_id,correlation_id,created_at,next_attempt_at)
 VALUES(NEW.tenant_id,NEW.id,NEW.operation_id,NEW.accepted_at,NEW.accepted_at+interval '1 minute');
 RETURN NEW;
END $$;
ALTER FUNCTION taska.enqueue_payment_reconciliation() OWNER TO taska_billing_migrator;
CREATE TRIGGER enqueue_payment_reconciliation AFTER INSERT ON taska.payment_attempts
 FOR EACH ROW EXECUTE FUNCTION taska.enqueue_payment_reconciliation();
INSERT INTO taska.payment_reconciliation(tenant_id,attempt_id,correlation_id,created_at,next_attempt_at)
 SELECT tenant_id,id,operation_id,accepted_at,accepted_at+interval '1 minute' FROM taska.payment_attempts;
CREATE FUNCTION taska.protect_payment_reconciliation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF ROW(NEW.tenant_id,NEW.attempt_id,NEW.correlation_id,NEW.created_at) IS DISTINCT FROM
    ROW(OLD.tenant_id,OLD.attempt_id,OLD.correlation_id,OLD.created_at)
    OR NEW.attempts<OLD.attempts OR (OLD.state='DONE' AND NEW IS DISTINCT FROM OLD) THEN
  RAISE EXCEPTION 'Immutable reconciliation origin or completed work' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_payment_reconciliation BEFORE UPDATE ON taska.payment_reconciliation
 FOR EACH ROW EXECUTE FUNCTION taska.protect_payment_reconciliation();
GRANT SELECT,UPDATE ON taska.payment_reconciliation TO taska_billing_app;
REVOKE DELETE,TRUNCATE ON taska.payment_reconciliation FROM taska_billing_app;
