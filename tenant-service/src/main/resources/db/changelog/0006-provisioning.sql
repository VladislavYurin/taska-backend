SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.tenant_operations (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES taska.tenants(id),
 kind text NOT NULL CHECK(kind='TENANT_PROVISION'), actor_id uuid NOT NULL,
 channel text NOT NULL CHECK(channel='WEB_GLOBAL'), request_key varchar(128) NOT NULL,
 request_hash bytea NOT NULL, state text NOT NULL DEFAULT 'ACCEPTED'
 CHECK(state IN ('ACCEPTED','RUNNING','SUCCEEDED','FAILED')),
 fence bigint NOT NULL DEFAULT 1 CHECK(fence>0),
 payload jsonb NOT NULL, result jsonb NOT NULL DEFAULT '{}'::jsonb,
 completed_steps integer NOT NULL DEFAULT 0 CHECK(completed_steps BETWEEN 0 AND 2),
 lease_until timestamptz, failure_code text,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,actor_id,kind,request_key)
);
CREATE INDEX tenant_operations_pending ON taska.tenant_operations(tenant_id,updated_at,id)
 WHERE state IN ('ACCEPTED','RUNNING');
-- The dispatch index contains routing IDs only. Operation data remains behind FORCE RLS.
CREATE TABLE taska.tenant_provisioning_dispatch (
 tenant_id uuid NOT NULL, operation_id uuid NOT NULL,
 next_attempt_at timestamptz NOT NULL, completed_at timestamptz,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES taska.tenant_operations(tenant_id,id)
);
CREATE INDEX tenant_provisioning_due ON taska.tenant_provisioning_dispatch(next_attempt_at,tenant_id,operation_id)
 WHERE completed_at IS NULL;
REVOKE ALL ON taska.tenant_provisioning_dispatch FROM PUBLIC;
GRANT SELECT,INSERT ON taska.tenant_provisioning_dispatch TO taska_tenant_app;
GRANT UPDATE(next_attempt_at,completed_at) ON taska.tenant_provisioning_dispatch TO taska_tenant_app;
CREATE FUNCTION taska.protect_provisioning_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.kind,NEW.actor_id,NEW.channel,NEW.request_key,NEW.request_hash,NEW.payload,NEW.created_at)
 IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.kind,OLD.actor_id,OLD.channel,OLD.request_key,OLD.request_hash,OLD.payload,OLD.created_at)
 THEN RAISE EXCEPTION 'Immutable provisioning input' USING ERRCODE='23514'; END IF;
 IF OLD.state IN ('SUCCEEDED','FAILED') AND NEW IS DISTINCT FROM OLD THEN
   RAISE EXCEPTION 'Terminal provisioning operation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_provisioning_input BEFORE UPDATE ON taska.tenant_operations
 FOR EACH ROW EXECUTE FUNCTION taska.protect_provisioning_input();
-- Upgrade pending registry creations without changing their durable operation identity.
ALTER TABLE taska.tenants NO FORCE ROW LEVEL SECURITY;
INSERT INTO taska.tenant_operations(id,tenant_id,kind,actor_id,channel,request_key,request_hash,payload,created_at,updated_at)
 SELECT creation_operation_id,id,'TENANT_PROVISION',creation_actor_id,'WEB_GLOBAL',creation_operation_id::text,
 creation_request_hash,jsonb_build_object('planVersionId',plan_version_id::text,'creationKind',creation_kind,
 'templateId','11111111-1111-1111-1111-111111111111'),created_at,updated_at
 FROM taska.tenants WHERE lifecycle IN ('PROVISIONING','AWAITING_PAYMENT');
INSERT INTO taska.tenant_provisioning_dispatch(tenant_id,operation_id,next_attempt_at)
 SELECT tenant_id,id,updated_at FROM taska.tenant_operations;
ALTER TABLE taska.tenants FORCE ROW LEVEL SECURITY;
