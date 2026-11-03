DO $bootstrap$
DECLARE
  service text := current_setting('taska.bootstrap_service');
  target_schema text := current_setting('taska.bootstrap_schema');
  suffix text; role_name text; owner_name text; deploy_name text; r record;
BEGIN
  IF service NOT IN ('auth','tenant','billing','project','workflow','issue','notification','admin','oauth')
     OR target_schema !~ '^[a-z][a-z0-9_]{0,31}$' OR target_schema IN ('public','information_schema')
     OR target_schema LIKE 'pg_%' THEN RAISE EXCEPTION 'Invalid service/schema'; END IF;
  owner_name := 'taska_' || service || '_migrator';
  deploy_name := 'taska_' || service || '_deployer';
  FOREACH suffix IN ARRAY ARRAY['migrator','deployer','app','admin','job','dispatcher'] LOOP
    role_name := 'taska_' || service || '_' || suffix;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name) THEN
      EXECUTE format('CREATE ROLE %I %s NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS',
                     role_name, CASE WHEN suffix='migrator' THEN 'NOLOGIN' ELSE 'LOGIN' END);
    END IF;
    SELECT * INTO r FROM pg_roles WHERE rolname=role_name;
    IF r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolinherit OR r.rolreplication OR r.rolbypassrls
       OR r.rolcanlogin <> (suffix <> 'migrator') THEN
      RAISE EXCEPTION 'Existing role has incompatible privileges: %', role_name;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member=r.oid
      AND NOT (suffix='deployer' AND m.roleid=(SELECT oid FROM pg_roles WHERE rolname=owner_name)
               AND NOT m.admin_option AND NOT m.inherit_option AND m.set_option)) THEN
      RAISE EXCEPTION 'Unexpected role membership: %', role_name;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.roleid=r.oid AND NOT (
      suffix='migrator' AND COALESCE(m.member=(SELECT oid FROM pg_roles WHERE rolname=deploy_name)
      AND NOT m.admin_option AND NOT m.inherit_option AND m.set_option,false))) THEN
      RAISE EXCEPTION 'Unexpected incoming role membership: %',role_name; END IF;
  END LOOP;
  EXECUTE format('GRANT %I TO %I WITH INHERIT FALSE, SET TRUE, ADMIN FALSE',owner_name,deploy_name);
  EXECUTE format('REVOKE CREATE,TEMPORARY,CONNECT ON DATABASE %I FROM PUBLIC',current_database());
  FOREACH suffix IN ARRAY ARRAY['deployer','app','admin','job','dispatcher'] LOOP
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I',current_database(),'taska_'||service||'_'||suffix);
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname=target_schema) THEN
    EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I',target_schema,owner_name);
  END IF;
  IF (SELECT nspowner FROM pg_namespace WHERE nspname=target_schema) <> (SELECT oid FROM pg_roles WHERE rolname=owner_name) THEN
    RAISE EXCEPTION 'Schema is not owned by migrator';
  END IF;
  EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC',target_schema);
  FOREACH suffix IN ARRAY ARRAY['app','admin','job','dispatcher'] LOOP
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I',target_schema,'taska_'||service||'_'||suffix);
  END LOOP;
  EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC',owner_name,target_schema);
  EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC',owner_name,target_schema);
  EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC',owner_name);
END
$bootstrap$;
