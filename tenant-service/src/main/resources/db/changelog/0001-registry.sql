SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.tenants (
  id uuid NOT NULL,
  name varchar(128) NOT NULL,
  owner_id uuid NOT NULL,
  lifecycle text NOT NULL,
  creation_kind text NOT NULL,
  creation_deadline_at timestamptz,
  first_payment_fact_id uuid,
  version bigint NOT NULL DEFAULT 1,
  membership_version bigint NOT NULL DEFAULT 1,
  restriction_version bigint NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  owner_version bigint NOT NULL DEFAULT 1,
  plan_version_id uuid NOT NULL,
  creation_actor_id uuid NOT NULL,
  creation_request_hash bytea NOT NULL,
  creation_operation_id uuid NOT NULL,
  PRIMARY KEY (id),
  CHECK (lifecycle IN ('PROVISIONING','AWAITING_PAYMENT','ACTIVE','AUTO_CLOSED','OWNER_CANCELLED','DELETING','DELETED')),
  CHECK (creation_kind IN ('FREE','PAID')),
  CHECK (version>0 AND membership_version>0 AND restriction_version>0),
  CHECK (owner_version>0)
);
CREATE INDEX tenants_lookup_0 ON taska.tenants(owner_id,id);
CREATE INDEX tenants_lookup_1 ON taska.tenants(creation_deadline_at,id) WHERE lifecycle='AWAITING_PAYMENT';
CREATE TABLE taska.tenant_memberships (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role text NOT NULL,
  status text NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  joined_at timestamptz NOT NULL DEFAULT now(),
  suspended_at timestamptz,
  removed_at timestamptz,
  PRIMARY KEY (tenant_id,user_id),
  CHECK (role IN ('TENANT_ADMIN','MEMBER')),
  CHECK (status IN ('ACTIVE','SUSPENDED','REMOVED')),
  CHECK (version>0)
);
CREATE INDEX tenant_memberships_lookup_0 ON taska.tenant_memberships(user_id,status,tenant_id);
CREATE INDEX tenant_memberships_lookup_1 ON taska.tenant_memberships(tenant_id,role,status,user_id);
CREATE TABLE taska.idempotency_records (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  operation_code text NOT NULL,
  key varchar(128) NOT NULL,
  request_hash bytea NOT NULL,
  operation_id uuid NOT NULL,
  state text NOT NULL,
  response jsonb,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id,actor_id,operation_code,key),
  CHECK (state IN ('IN_PROGRESS','SUCCEEDED','FAILED')),
  CHECK (expires_at>created_at)
);
CREATE TABLE taska.outbox_events (
  id uuid NOT NULL,
  tenant_id uuid NOT NULL,
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type text NOT NULL,
  schema_version integer NOT NULL,
  payload jsonb NOT NULL,
  operation_id uuid NOT NULL,
  status text NOT NULL,
  created_at timestamptz NOT NULL,
  published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  processing_started_at timestamptz,
  last_error_code text,
  PRIMARY KEY (id),
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type),
  CHECK (status IN ('NEW','PROCESSING','PUBLISHED','FAILED')),
  CHECK (attempts>=0 AND schema_version>0)
);
CREATE INDEX outbox_events_lookup_0 ON taska.outbox_events(tenant_id,status,created_at,id) WHERE status IN ('NEW','PROCESSING');
CREATE TABLE taska.global_outbox_events (
  id uuid NOT NULL,
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type text NOT NULL,
  schema_version integer NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL,
  created_at timestamptz NOT NULL,
  published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE (aggregate_type,aggregate_id,aggregate_version,event_type),
  CHECK (status IN ('NEW','PROCESSING','PUBLISHED','FAILED'))
);
CREATE INDEX global_outbox_events_lookup_0 ON taska.global_outbox_events(status,created_at,id) WHERE status IN ('NEW','PROCESSING');
CREATE TABLE taska.processed_events (
  tenant_id uuid NOT NULL,
  consumer text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL,
  result_version bigint,
  PRIMARY KEY (tenant_id,consumer,event_id)
);
ALTER TABLE taska.tenants ADD CONSTRAINT tenants_fk_0 FOREIGN KEY (id,owner_id) REFERENCES taska.tenant_memberships (tenant_id,user_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE taska.tenant_memberships ADD CONSTRAINT tenant_memberships_fk_0 FOREIGN KEY (tenant_id) REFERENCES taska.tenants (id) ON DELETE RESTRICT;
CREATE FUNCTION taska.protect_tenant_creation() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 IF (NEW.id,NEW.creation_actor_id,NEW.creation_kind,NEW.plan_version_id,NEW.creation_deadline_at,NEW.creation_request_hash,NEW.creation_operation_id,NEW.created_at)
 IS DISTINCT FROM (OLD.id,OLD.creation_actor_id,OLD.creation_kind,OLD.plan_version_id,OLD.creation_deadline_at,OLD.creation_request_hash,OLD.creation_operation_id,OLD.created_at)
 THEN RAISE EXCEPTION 'Immutable tenant creation facts' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER immutable_tenant_creation BEFORE UPDATE ON taska.tenants FOR EACH ROW EXECUTE FUNCTION taska.protect_tenant_creation();
ALTER TABLE taska.tenants ADD CONSTRAINT tenant_creation_shape CHECK (
 (creation_kind='PAID' AND creation_deadline_at IS NOT NULL) OR (creation_kind='FREE' AND creation_deadline_at IS NULL));
