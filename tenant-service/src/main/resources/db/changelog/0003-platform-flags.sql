SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
-- Global configuration has no nullable tenant scope and no tenant override.
CREATE TABLE taska.platform_configuration_definitions (
 key text PRIMARY KEY, value_type text NOT NULL CHECK(value_type IN ('BOOLEAN','INTEGER','DURATION_SECONDS')),
 default_value jsonb NOT NULL, owner text NOT NULL,
 minimum_value bigint, maximum_value bigint,
 CHECK ((value_type='BOOLEAN' AND jsonb_typeof(default_value)='boolean' AND minimum_value IS NULL AND maximum_value IS NULL)
 OR (value_type IN ('INTEGER','DURATION_SECONDS') AND jsonb_typeof(default_value)='number'
 AND minimum_value>0 AND maximum_value>=minimum_value))
);
INSERT INTO taska.platform_configuration_definitions(key,value_type,default_value,owner) SELECT key,'BOOLEAN','true'::jsonb,owner FROM (VALUES
 ('READ_WHILE_RESTRICTED','tenant-service'),('AUTOPAY','billing-service'),
 ('PAID_TENANT_TRANSFER','tenant-service'),('OWNER_PAUSE','tenant-service'),
 ('INVITATIONS','tenant-service'),('PROJECT_CREATION','project-service'),
 ('ATTACHMENT_UPLOAD','issue-service'),('EXPORT','issue-service'),('MCP','mcp-server'),
 ('WORKFLOW_CONFIGURATION','workflow-service'),('TELEGRAM','notification-service'),
 ('EMAIL','notification-service'),('ISSUE_TYPE_CONFIGURATION','project-service'),
 ('PUBLIC_SIGNUP','auth-service'),('PERSONAL_API_TOKENS','auth-service'),
 ('INBOX','notification-service'),('SOCIAL_SIGN_IN','oauth-server'),
 ('AVATAR_UPLOAD','auth-service'),('WIRE_PAYMENT','billing-service'),('PSP_PAYMENT','billing-service')
) AS flags(key,owner);
INSERT INTO taska.platform_configuration_definitions VALUES
 ('FREE_MCP','BOOLEAN','false','mcp-server',NULL,NULL),
 ('FREE_PERSONAL_API_TOKENS','BOOLEAN','false','auth-service',NULL,NULL),
 ('UNPAID_CREATION_WINDOW_SECONDS','DURATION_SECONDS','2592000','tenant-service',1,31536000),
 ('WIRE_RECONCILIATION_TARGET_SECONDS','DURATION_SECONDS','86400','billing-service',1,31536000);
CREATE TABLE taska.platform_configuration_versions (
 key text NOT NULL REFERENCES taska.platform_configuration_definitions(key),
 version bigint NOT NULL CHECK(version>0), value_type text NOT NULL, value jsonb NOT NULL,
 effective_at timestamptz NOT NULL, changed_by uuid, created_at timestamptz NOT NULL,
 PRIMARY KEY(key,version),
 CHECK(changed_by IS NOT NULL OR version=1)
);
CREATE INDEX platform_configuration_effective_idx ON taska.platform_configuration_versions(key,effective_at DESC,version DESC);
INSERT INTO taska.platform_configuration_versions SELECT key,1,value_type,default_value,now(),NULL,now()
 FROM taska.platform_configuration_definitions;
CREATE FUNCTION taska.validate_platform_configuration() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE d taska.platform_configuration_definitions; n bigint;
BEGIN
 SELECT * INTO STRICT d FROM taska.platform_configuration_definitions WHERE key=NEW.key;
 IF NEW.value_type<>d.value_type THEN RAISE EXCEPTION 'Invalid configuration type' USING ERRCODE='23514'; END IF;
 IF d.value_type='BOOLEAN' THEN
   IF jsonb_typeof(NEW.value)<>'boolean' THEN RAISE EXCEPTION 'Invalid boolean' USING ERRCODE='23514'; END IF;
 ELSE
   IF jsonb_typeof(NEW.value)<>'number' OR NEW.value::text !~ '^[1-9][0-9]{0,18}$' THEN
     RAISE EXCEPTION 'Invalid integer' USING ERRCODE='23514'; END IF;
   n := NEW.value::text::bigint;
   IF n<d.minimum_value OR n>d.maximum_value THEN RAISE EXCEPTION 'Out of range' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_platform_configuration BEFORE INSERT ON taska.platform_configuration_versions
 FOR EACH ROW EXECUTE FUNCTION taska.validate_platform_configuration();
CREATE TABLE taska.platform_configuration_receipts (
 actor_id uuid NOT NULL, key varchar(128) NOT NULL, request_hash bytea NOT NULL,
 flag_key text NOT NULL, flag_version bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_id,key), FOREIGN KEY(flag_key,flag_version) REFERENCES taska.platform_configuration_versions(key,version)
);
-- The immutable version row is the audit record: actor, time, typed value and exact version.
CREATE FUNCTION taska.protect_platform_configuration() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable configuration history' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_platform_configuration BEFORE UPDATE OR DELETE ON taska.platform_configuration_versions
 FOR EACH ROW EXECUTE FUNCTION taska.protect_platform_configuration();
CREATE TRIGGER immutable_platform_configuration_receipt BEFORE UPDATE OR DELETE ON taska.platform_configuration_receipts
 FOR EACH ROW EXECUTE FUNCTION taska.protect_platform_configuration();
REVOKE ALL ON taska.platform_configuration_definitions,taska.platform_configuration_versions,taska.platform_configuration_receipts FROM PUBLIC,taska_tenant_app;
GRANT SELECT ON taska.platform_configuration_definitions,taska.platform_configuration_versions,taska.platform_configuration_receipts TO taska_tenant_app;
GRANT INSERT ON taska.platform_configuration_versions,taska.platform_configuration_receipts TO taska_tenant_app;
-- Existing global outbox was reserved without runtime grants; this producer only appends.
GRANT INSERT ON taska.global_outbox_events TO taska_tenant_app;
