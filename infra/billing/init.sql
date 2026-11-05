-- Fresh private PostgreSQL database only; secrets come from container environment.
\set service billing
\set schema taska
\i /opt/taska/templates/bootstrap.psql
CREATE SCHEMA IF NOT EXISTS billing_liquibase AUTHORIZATION taska_billing_migrator;
DO $$ BEGIN IF (SELECT nspowner FROM pg_namespace WHERE nspname='billing_liquibase') <> 'taska_billing_migrator'::regrole THEN RAISE EXCEPTION 'Unexpected Liquibase schema owner'; END IF; END $$;
REVOKE ALL ON SCHEMA billing_liquibase FROM PUBLIC;
\getenv app_password BILLING_APP_PASSWORD
\getenv deploy_password BILLING_DEPLOY_PASSWORD
ALTER ROLE taska_billing_app PASSWORD :'app_password';
ALTER ROLE taska_billing_deployer PASSWORD :'deploy_password';
