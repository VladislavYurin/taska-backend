-- TAS-330 v2: PostgreSQL table identifiers up to 63 ASCII characters; v1 remains immutable.
DO $audit$
DECLARE
  access_profile text:=current_setting('taska.audit_app_access');
  service text:=current_setting('taska.audit_service'); s text:=current_setting('taska.audit_schema');
  t text:=current_setting('taska.audit_table'); c text:=current_setting('taska.audit_column');
  role_name text; suffix text; owner_name text; relation regclass; r record; policy record; expected text;
BEGIN
  IF access_profile NOT IN ('read_write','read_only','append_only') THEN RAISE EXCEPTION 'Invalid app access profile'; END IF;
  IF service NOT IN ('auth','tenant','billing','project','workflow','issue','notification','admin','oauth')
    OR s !~ '^[a-z][a-z0-9_]{0,31}$' OR t !~ '^[a-z][a-z0-9_]{0,62}$' OR c NOT IN ('tenant_id','id') THEN
    RAISE EXCEPTION 'Invalid audit inputs'; END IF;
  owner_name:='taska_'||service||'_migrator';relation:=format('%I.%I',s,t)::regclass;
  IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname=s AND nspowner=owner_name::regrole) THEN
    RAISE EXCEPTION 'Schema ownership mismatch'; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid=relation AND relowner=owner_name::regrole AND relrowsecurity AND relforcerowsecurity) THEN
    RAISE EXCEPTION 'Table ownership/RLS mismatch'; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid=relation AND attname=c AND attnotnull AND atttypid='uuid'::regtype AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Tenant column must be UUID NOT NULL'; END IF;
  IF (SELECT count(*) FROM pg_policy WHERE polrelid=relation)<>1 THEN RAISE EXCEPTION 'Unexpected policy count'; END IF;
  SELECT * INTO policy FROM pg_policy WHERE polrelid=relation AND polname='tenant_guard';
  IF NOT FOUND OR policy.polcmd<>'*' OR NOT policy.polpermissive
    OR NOT (policy.polroles @> ARRAY[('taska_'||service||'_app')::regrole::oid,('taska_'||service||'_admin')::regrole::oid,('taska_'||service||'_job')::regrole::oid])
    OR cardinality(policy.polroles)<>3 THEN RAISE EXCEPTION 'Policy role/command mismatch'; END IF;
  expected:=format('%I = NULLIF(current_setting(''app.tenant_id''::text,true),''''::text)::uuid',c);
  IF lower(regexp_replace(pg_get_expr(policy.polqual,relation),'[[:space:]()]','','g')) IS DISTINCT FROM lower(regexp_replace(expected,'[[:space:]()]','','g'))
     OR pg_get_expr(policy.polqual,relation) IS DISTINCT FROM pg_get_expr(policy.polwithcheck,relation) THEN
    RAISE EXCEPTION 'Policy predicate mismatch'; END IF;
  -- NULL relacl means built-in defaults; ALTER DEFAULT PRIVILEGES is materialized at CREATE.
  -- Effective privileges and role memberships are also checked below.
  IF EXISTS (SELECT 1 FROM pg_class x CROSS JOIN LATERAL aclexplode(COALESCE(x.relacl,acldefault('r',x.relowner))) acl
      WHERE x.oid=relation AND acl.grantee<>ALL(ARRAY[owner_name::regrole::oid,('taska_'||service||'_app')::regrole::oid,
        ('taska_'||service||'_admin')::regrole::oid,('taska_'||service||'_job')::regrole::oid]))
     OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid=relation AND attacl IS NOT NULL) THEN
    RAISE EXCEPTION 'Unexpected table/column grantee'; END IF;
  SELECT * INTO r FROM pg_roles WHERE rolname=owner_name;
  IF NOT FOUND OR r.rolsuper OR r.rolbypassrls OR r.rolcanlogin OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolinherit
    OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
    OR EXISTS (SELECT 1 FROM pg_auth_members WHERE roleid=r.oid AND NOT (
      member=('taska_'||service||'_deployer')::regrole::oid AND NOT admin_option AND NOT inherit_option AND set_option)) THEN
    RAISE EXCEPTION 'Migration owner privileges/membership mismatch'; END IF;
  SELECT * INTO r FROM pg_roles WHERE rolname='taska_'||service||'_deployer';
  IF NOT FOUND OR NOT r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolinherit OR r.rolreplication
    OR EXISTS (SELECT 1 FROM pg_auth_members WHERE roleid=r.oid)
    OR NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid AND roleid=owner_name::regrole AND set_option AND NOT inherit_option AND NOT admin_option)
    OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid AND NOT (roleid=owner_name::regrole AND set_option AND NOT inherit_option AND NOT admin_option)) THEN
    RAISE EXCEPTION 'Deployment role privileges/membership mismatch'; END IF;
  IF EXISTS (SELECT 1 FROM pg_class x CROSS JOIN LATERAL aclexplode(x.relacl) acl
    WHERE x.oid=relation AND acl.grantee<>x.relowner AND acl.is_grantable) THEN
    RAISE EXCEPTION 'Runtime must not have grant options'; END IF;
  -- Non-RLS global parents are explicit inventory exclusions, not inferred tenant tables.
  -- Both child and parent leading attributes must be their tenant columns.
  IF EXISTS (SELECT 1 FROM pg_constraint fk JOIN pg_class parent ON parent.oid=fk.confrelid
      JOIN pg_attribute child_col ON child_col.attrelid=relation AND child_col.attname=c
      JOIN pg_attribute parent_col ON parent_col.attrelid=parent.oid AND parent_col.attname='tenant_id' AND NOT parent_col.attisdropped
      WHERE fk.conrelid=relation AND fk.contype='f' AND parent.relrowsecurity
        AND (fk.conkey[1]<>child_col.attnum OR fk.confkey[1]<>parent_col.attnum)) THEN
    RAISE EXCEPTION 'Tenant foreign key must include tenant as its leading column'; END IF;
  FOREACH suffix IN ARRAY ARRAY['app','admin','job','dispatcher'] LOOP
    role_name:='taska_'||service||'_'||suffix;
    SELECT * INTO r FROM pg_roles WHERE rolname=role_name;
    IF NOT FOUND OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolinherit OR r.rolreplication OR NOT r.rolcanlogin
      OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
      OR NOT has_database_privilege(role_name,current_database(),'CONNECT')
      OR has_schema_privilege(role_name,s,'CREATE')
      OR has_database_privilege(role_name,current_database(),'CREATE')
      OR has_database_privilege(role_name,current_database(),'TEMPORARY')
      OR has_table_privilege(role_name,relation,'TRUNCATE,TRIGGER,REFERENCES') THEN
      RAISE EXCEPTION 'Runtime privilege mismatch: %',role_name; END IF;
    IF suffix='app' AND (NOT has_table_privilege(role_name,relation,'SELECT')
      OR has_table_privilege(role_name,relation,'INSERT') IS DISTINCT FROM (access_profile<>'read_only')
      OR has_table_privilege(role_name,relation,'UPDATE') IS DISTINCT FROM (access_profile='read_write')
      OR has_table_privilege(role_name,relation,'DELETE') IS DISTINCT FROM (access_profile='read_write')) THEN
      RAISE EXCEPTION 'Application DML profile mismatch'; END IF;
    IF suffix='admin' AND NOT has_table_privilege(role_name,relation,'SELECT') THEN RAISE EXCEPTION 'Missing admin read grant'; END IF;
    IF suffix='admin' AND has_table_privilege(role_name,relation,'INSERT,UPDATE,DELETE') THEN RAISE EXCEPTION 'Admin projection must be read-only'; END IF;
    IF suffix='dispatcher' AND has_table_privilege(role_name,relation,'SELECT,INSERT,UPDATE,DELETE') THEN RAISE EXCEPTION 'Dispatcher has business-table access'; END IF;
  END LOOP;
  IF c='tenant_id' AND NOT EXISTS (
    SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
    WHERE i.indrelid=relation AND i.indisvalid AND i.indisready AND i.indpred IS NULL AND a.attname=c
  ) THEN RAISE EXCEPTION 'Missing tenant-leading index'; END IF;
END
$audit$;
