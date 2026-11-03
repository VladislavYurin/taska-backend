#!/bin/sh
set -eu
# Only called by the isolated PostgreSQL image on an empty private data directory.
psql -X -q -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\set service tenant
\set schema taska
\i /opt/taska/templates/bootstrap.psql
CREATE SCHEMA IF NOT EXISTS tenant_liquibase AUTHORIZATION taska_tenant_migrator;
DO $$ BEGIN IF (SELECT nspowner FROM pg_namespace WHERE nspname='tenant_liquibase') <> 'taska_tenant_migrator'::regrole THEN RAISE EXCEPTION 'Unexpected Liquibase schema owner'; END IF; END $$;
REVOKE ALL ON SCHEMA tenant_liquibase FROM PUBLIC;
\getenv app_password TENANT_APP_PASSWORD
\getenv deploy_password TENANT_DEPLOY_PASSWORD
ALTER ROLE taska_tenant_app PASSWORD :'app_password';
ALTER ROLE taska_tenant_deployer PASSWORD :'deploy_password';
SQL
