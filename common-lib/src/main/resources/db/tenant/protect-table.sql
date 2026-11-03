DO $guard$
DECLARE
  access_profile text:=current_setting('taska.guard_app_access');
  service text:=current_setting('taska.guard_service');
  s text:=current_setting('taska.guard_schema');
  t text:=current_setting('taska.guard_table');
  c text:=current_setting('taska.guard_column');
  relation regclass; owner_name text; app text; admin_role text; job text; expression text;
BEGIN
  IF access_profile NOT IN ('read_write','read_only','append_only') THEN RAISE EXCEPTION 'Invalid app access profile'; END IF;
  IF service NOT IN ('auth','tenant','billing','project','workflow','issue','notification','admin','oauth')
    OR s !~ '^[a-z][a-z0-9_]{0,31}$' OR t !~ '^[a-z][a-z0-9_]{0,31}$'
    OR c NOT IN ('tenant_id','id') THEN RAISE EXCEPTION 'Invalid tenant table inputs'; END IF;
  owner_name:='taska_'||service||'_migrator';
  IF current_user<>owner_name THEN RAISE EXCEPTION 'Migration owner role required'; END IF;
  relation:=format('%I.%I',s,t)::regclass;
  IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid=relation AND relowner=current_user::regrole AND relkind='r') THEN
    RAISE EXCEPTION 'Expected an ordinary table owned by migrator'; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid=relation AND attname=c AND atttypid='uuid'::regtype AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Expected UUID tenant column'; END IF;
  -- Refuse additional policies: permissive policies are OR-combined and could bypass this guard.
  IF EXISTS (SELECT 1 FROM pg_policy WHERE polrelid=relation AND polname<>'tenant_guard') THEN
    RAISE EXCEPTION 'Unexpected existing RLS policy'; END IF;
  IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid=relation AND attacl IS NOT NULL) THEN
    RAISE EXCEPTION 'Column-level grants require explicit review'; END IF;
  app:='taska_'||service||'_app';admin_role:='taska_'||service||'_admin';job:='taska_'||service||'_job';
  EXECUTE format('ALTER TABLE %s ALTER COLUMN %I SET NOT NULL',relation,c);
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY',relation);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',relation);
  EXECUTE format('DROP POLICY IF EXISTS tenant_guard ON %s',relation);
  expression:=format('%I = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid',c);
  EXECUTE format('CREATE POLICY tenant_guard ON %s TO %I,%I,%I USING (%s) WITH CHECK (%s)',relation,app,admin_role,job,expression,expression);
  EXECUTE format('REVOKE ALL ON TABLE %s FROM PUBLIC,%I,%I,%I,%I',relation,app,admin_role,job,'taska_'||service||'_dispatcher');
  EXECUTE format('GRANT %s ON TABLE %s TO %I',
    CASE access_profile WHEN 'read_write' THEN 'SELECT,INSERT,UPDATE,DELETE' WHEN 'append_only' THEN 'SELECT,INSERT' ELSE 'SELECT' END,relation,app);
  EXECUTE format('GRANT SELECT ON TABLE %s TO %I',relation,admin_role);
  -- job DML is service-specific: grant it explicitly in the owning migration, never blanket DML.
  IF c='tenant_id' THEN
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %s (tenant_id)',t||'_tenant_guard_idx',relation);
    IF NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
      WHERE i.indexrelid=to_regclass(format('%I.%I',s,t||'_tenant_guard_idx')) AND i.indrelid=relation
      AND i.indisvalid AND i.indisready AND i.indpred IS NULL AND i.indexprs IS NULL AND a.attname=c) THEN
      RAISE EXCEPTION 'Existing tenant index has incompatible definition'; END IF;
  END IF;
END
$guard$;
