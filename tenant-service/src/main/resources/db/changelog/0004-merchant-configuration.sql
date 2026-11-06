SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
-- One closed structured policy, deliberately excluded from public/scalar flag DTOs.
ALTER TABLE taska.platform_configuration_definitions DROP CONSTRAINT platform_configuration_definitions_value_type_check;
ALTER TABLE taska.platform_configuration_definitions DROP CONSTRAINT platform_configuration_definitions_check;
ALTER TABLE taska.platform_configuration_definitions ADD CONSTRAINT platform_configuration_definition_shape CHECK (
 (value_type='BOOLEAN' AND jsonb_typeof(default_value)='boolean' AND minimum_value IS NULL AND maximum_value IS NULL)
 OR (value_type IN ('INTEGER','DURATION_SECONDS') AND jsonb_typeof(default_value)='number' AND minimum_value>0 AND maximum_value>=minimum_value)
 OR (key='MERCHANT_CONFIGURATION' AND value_type='JSON' AND jsonb_typeof(default_value)='object' AND minimum_value IS NULL AND maximum_value IS NULL)
);
INSERT INTO taska.platform_configuration_definitions VALUES
 ('MERCHANT_CONFIGURATION','JSON','{"schemaVersion":1,"environment":"COMMERCIAL_ENVIRONMENT_LIVE"}','billing-service',NULL,NULL);
CREATE OR REPLACE FUNCTION taska.validate_platform_configuration() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE d taska.platform_configuration_definitions; n bigint;
BEGIN
 SELECT * INTO STRICT d FROM taska.platform_configuration_definitions WHERE key=NEW.key;
 IF NEW.value_type<>d.value_type THEN RAISE EXCEPTION 'Invalid configuration type' USING ERRCODE='23514'; END IF;
 IF d.value_type='JSON' THEN
   IF NEW.key<>'MERCHANT_CONFIGURATION' OR jsonb_typeof(NEW.value)<>'object' OR pg_column_size(NEW.value)>65536
      OR NEW.value->>'schemaVersion' IS DISTINCT FROM '1' OR NOT (NEW.value ? 'environment') THEN
     RAISE EXCEPTION 'Invalid commercial configuration shape' USING ERRCODE='23514'; END IF;
 ELSIF d.value_type='BOOLEAN' THEN
   IF jsonb_typeof(NEW.value)<>'boolean' THEN RAISE EXCEPTION 'Invalid boolean' USING ERRCODE='23514'; END IF;
 ELSE
   IF jsonb_typeof(NEW.value)<>'number' OR NEW.value::text !~ '^[1-9][0-9]{0,18}$' THEN RAISE EXCEPTION 'Invalid integer' USING ERRCODE='23514'; END IF;
   n := NEW.value::text::bigint;
   IF n<d.minimum_value OR n>d.maximum_value THEN RAISE EXCEPTION 'Out of range' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
INSERT INTO taska.platform_configuration_versions SELECT key,1,value_type,default_value,now(),NULL,now()
 FROM taska.platform_configuration_definitions WHERE key='MERCHANT_CONFIGURATION';
