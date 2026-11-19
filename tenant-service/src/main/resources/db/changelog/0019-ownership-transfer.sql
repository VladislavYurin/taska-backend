SET LOCAL ROLE taska_tenant_migrator;
CREATE TABLE taska.tenant_ownership_transfers (
 tenant_id uuid NOT NULL,id uuid NOT NULL,actor_id uuid NOT NULL,authority text NOT NULL,
 request_key uuid NOT NULL,request_hash bytea NOT NULL,
 old_owner_id uuid NOT NULL,old_owner_version bigint NOT NULL,new_owner_id uuid NOT NULL,
 expected_tenant_version bigint NOT NULL,administrative boolean NOT NULL,
 state text NOT NULL CHECK(state IN ('PENDING','COMMITTED','CANCELLED')),
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,
 committed_at timestamptz,billing_fence_until timestamptz,response text,failure_code text,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,actor_id,authority,request_key),
 CHECK(old_owner_id<>new_owner_id),CHECK(old_owner_version>0 AND expected_tenant_version>0),
 CHECK(expires_at>created_at),
 CHECK((state='COMMITTED')=(committed_at IS NOT NULL AND response IS NOT NULL AND billing_fence_until IS NOT NULL))
);
CREATE TABLE taska.tenant_ownership_audit (
 tenant_id uuid NOT NULL,operation_id uuid NOT NULL,actor_id uuid NOT NULL,
 old_owner_id uuid NOT NULL,new_owner_id uuid NOT NULL,owner_version bigint NOT NULL,
 administrative boolean NOT NULL,created_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES taska.tenant_ownership_transfers(tenant_id,id)
);
-- Только routing IDs, как существующий provisioning dispatch; права выдаёт online SERVICE policy.
CREATE TABLE taska.tenant_ownership_dispatch (
 tenant_id uuid NOT NULL,operation_id uuid NOT NULL,next_attempt_at timestamptz NOT NULL,
 completed_at timestamptz,PRIMARY KEY(tenant_id,operation_id)
);
CREATE INDEX tenant_ownership_due ON taska.tenant_ownership_dispatch(next_attempt_at) WHERE completed_at IS NULL;
REVOKE ALL ON taska.tenant_ownership_dispatch FROM PUBLIC;
GRANT SELECT,INSERT ON taska.tenant_ownership_dispatch TO taska_tenant_app;
GRANT UPDATE(next_attempt_at,completed_at) ON taska.tenant_ownership_dispatch TO taska_tenant_app;

-- Проверка при COMMIT не позволяет задержанной SQL-транзакции пережить оплаченный admission.
CREATE FUNCTION taska.check_ownership_commit_deadline() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.state='COMMITTED' AND clock_timestamp()+interval '5 seconds'>=NEW.billing_fence_until THEN
  RAISE EXCEPTION 'OWNERSHIP_OPERATION_EXPIRED' USING ERRCODE='40001';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ownership_commit_deadline AFTER INSERT OR UPDATE ON taska.tenant_ownership_transfers
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION taska.check_ownership_commit_deadline();
