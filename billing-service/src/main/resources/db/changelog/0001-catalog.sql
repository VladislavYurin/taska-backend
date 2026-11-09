SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
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

CREATE TABLE taska.catalog_plans (
 plan_code varchar(64) PRIMARY KEY CHECK (plan_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
 kind text NOT NULL CHECK (kind IN ('FREE','PAID')),
 created_at timestamptz NOT NULL DEFAULT now(), archived_at timestamptz
);
CREATE TABLE taska.catalog_plan_versions (
 id uuid PRIMARY KEY, plan_code varchar(64) NOT NULL REFERENCES taska.catalog_plans(plan_code),
 version bigint NOT NULL CHECK (version>0), name varchar(120) NOT NULL CHECK (length(trim(name))>0),
 price_minor bigint NOT NULL CHECK (price_minor>=0), currency char(3) NOT NULL CHECK(currency='EUR'),
 currency_exponent smallint NOT NULL CHECK(currency_exponent=2),
 period text NOT NULL CHECK(period IN ('NONE','CALENDAR_MONTH')),
 max_projects bigint NOT NULL CHECK(max_projects>0), max_members bigint NOT NULL CHECK(max_members>0),
 max_storage_bytes bigint NOT NULL CHECK(max_storage_bytes>=0),
 max_tasks_per_project bigint CHECK(max_tasks_per_project>=0),
 max_attachment_bytes bigint NOT NULL DEFAULT 20000000 CHECK(max_attachment_bytes>0),
 features jsonb NOT NULL CHECK(jsonb_typeof(features)='array'),
 published_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(plan_code,version),
 CHECK ((period='NONE' AND price_minor=0) OR (period='CALENDAR_MONTH' AND price_minor>0))
);
CREATE INDEX catalog_current_idx ON taska.catalog_plan_versions(plan_code,version DESC) WHERE published_at IS NOT NULL;
CREATE FUNCTION taska.immutable_catalog() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.published_at IS NOT NULL THEN
  RAISE EXCEPTION 'Immutable catalog history' USING ERRCODE='23514';
 END IF;
 IF (to_jsonb(NEW)-'published_at') IS DISTINCT FROM (to_jsonb(OLD)-'published_at') THEN
  RAISE EXCEPTION 'Immutable plan content' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_plan BEFORE UPDATE OR DELETE ON taska.catalog_plan_versions FOR EACH ROW EXECUTE FUNCTION taska.immutable_catalog();
CREATE TABLE taska.catalog_mutation_receipts (
 actor_id uuid NOT NULL, operation text NOT NULL, key varchar(128) NOT NULL,
 request_hash bytea NOT NULL, response text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_id,operation,key)
);
REVOKE ALL ON ALL TABLES IN SCHEMA taska FROM PUBLIC;
GRANT SELECT,INSERT ON taska.catalog_plans,taska.catalog_plan_versions,taska.catalog_mutation_receipts,taska.global_outbox_events TO taska_billing_app;
GRANT UPDATE(published_at) ON taska.catalog_plan_versions TO taska_billing_app;
-- Row locks require UPDATE permission, without permitting changes to product identity.
GRANT UPDATE(plan_code) ON taska.catalog_plans TO taska_billing_app;
CREATE FUNCTION taska.immutable_product() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable product identity' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_product BEFORE UPDATE OR DELETE ON taska.catalog_plans FOR EACH ROW EXECUTE FUNCTION taska.immutable_product();
INSERT INTO taska.catalog_plans(plan_code,kind) VALUES ('FREE','FREE'),('PAID','PAID');
INSERT INTO taska.catalog_plan_versions(id,plan_code,version,name,price_minor,currency,currency_exponent,period,max_projects,max_members,max_storage_bytes,max_tasks_per_project,features,published_at) VALUES
 ('00000000-0000-8000-8000-000000000001','FREE',1,'Free',0,'EUR',2,'NONE',1,5,100000000,50,'[]',now()),
 ('00000000-0000-8000-8000-000000000002','PAID',1,'Paid',2000,'EUR',2,'CALENDAR_MONTH',5,50,5000000000,NULL,'[]',now());
