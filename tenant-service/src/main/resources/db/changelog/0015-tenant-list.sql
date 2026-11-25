SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;

-- These policies apply only while the non-login migrator-owned list function runs.
-- The runtime app policy remains tenant_guard and is not widened.
CREATE POLICY tenant_list_actor_membership_guard ON taska.tenant_memberships
  FOR SELECT TO taska_tenant_migrator
  USING (
    user_id = NULLIF(current_setting('app.tenant_list_actor_id',true),'')::uuid
    AND status='ACTIVE'
  );

CREATE POLICY tenant_list_actor_registry_guard ON taska.tenants
  FOR SELECT TO taska_tenant_migrator
  USING (
    id IN (
      SELECT m.tenant_id
      FROM taska.tenant_memberships AS m
      WHERE m.user_id = NULLIF(current_setting('app.tenant_list_actor_id',true),'')::uuid
        AND m.status='ACTIVE'
    )
  );

CREATE FUNCTION taska.list_tenants_for_actor(
  p_actor uuid,
  p_after_created_at timestamptz,
  p_after_tenant_id uuid,
  p_limit integer
)
RETURNS TABLE (
  id uuid,
  name varchar(128),
  owner_id uuid,
  lifecycle text,
  created_at timestamptz,
  version bigint,
  owner_version bigint,
  plan_version_id uuid,
  creation_operation_id uuid,
  creation_kind text,
  creation_closure_reason text,
  creation_closed_at timestamptz,
  creation_deadline_at timestamptz,
  pending_first_payment boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog
AS $function$
DECLARE
  context_actor uuid;
BEGIN
  context_actor := NULLIF(current_setting('app.actor_id',true),'')::uuid;
  IF p_actor IS NULL OR context_actor IS DISTINCT FROM p_actor
     OR p_limit IS NULL OR p_limit < 1 OR p_limit > 101
     OR ((p_after_created_at IS NULL) <> (p_after_tenant_id IS NULL)) THEN
    RAISE EXCEPTION 'Invalid tenant list scope' USING ERRCODE='42501';
  END IF;

  PERFORM set_config('app.tenant_list_actor_id',p_actor::text,true);
  RETURN QUERY
    SELECT t.id,t.name,t.owner_id,t.lifecycle,t.created_at,t.version,t.owner_version,
      t.plan_version_id,t.creation_operation_id,t.creation_kind,t.creation_closure_reason,
      t.creation_closed_at,t.creation_deadline_at,
      (t.creation_kind='PAID' AND t.first_payment_fact_id IS NULL)
    FROM taska.tenant_memberships AS m
    JOIN taska.tenants AS t ON t.id=m.tenant_id
    WHERE m.user_id=p_actor
      AND m.status='ACTIVE'
      AND t.lifecycle<>'DELETED'
      AND (p_after_created_at IS NULL OR
        (t.created_at,t.id)>(p_after_created_at,p_after_tenant_id))
    ORDER BY t.created_at,t.id
    LIMIT p_limit;
  PERFORM set_config('app.tenant_list_actor_id','',true);
END
$function$;

REVOKE ALL ON FUNCTION taska.list_tenants_for_actor(uuid,timestamptz,uuid,integer)
  FROM PUBLIC,taska_tenant_admin,taska_tenant_job,taska_tenant_dispatcher;
GRANT EXECUTE ON FUNCTION taska.list_tenants_for_actor(uuid,timestamptz,uuid,integer)
  TO taska_tenant_app;

DO $guard$
DECLARE
  function_oid oid := 'taska.list_tenants_for_actor(uuid,timestamptz,uuid,integer)'::regprocedure;
  function_row record;
  tenant_row record;
  membership_row record;
BEGIN
  IF current_user<>'taska_tenant_migrator' THEN
    RAISE EXCEPTION 'Tenant migration owner role required';
  END IF;

  SELECT relrowsecurity,relforcerowsecurity,relowner INTO tenant_row
  FROM pg_class WHERE oid='taska.tenants'::regclass;
  IF NOT tenant_row.relrowsecurity OR NOT tenant_row.relforcerowsecurity
     OR tenant_row.relowner<>'taska_tenant_migrator'::regrole THEN
    RAISE EXCEPTION 'Tenant registry RLS invariant mismatch';
  END IF;

  SELECT relrowsecurity,relforcerowsecurity,relowner INTO membership_row
  FROM pg_class WHERE oid='taska.tenant_memberships'::regclass;
  IF NOT membership_row.relrowsecurity OR NOT membership_row.relforcerowsecurity
     OR membership_row.relowner<>'taska_tenant_migrator'::regrole THEN
    RAISE EXCEPTION 'Tenant membership RLS invariant mismatch';
  END IF;

  SELECT prosecdef,proowner,proconfig INTO function_row
  FROM pg_proc WHERE oid=function_oid;
  IF NOT function_row.prosecdef
     OR function_row.proowner<>'taska_tenant_migrator'::regrole
     OR NOT (function_row.proconfig @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Tenant list function owner/security mismatch';
  END IF;

  IF NOT has_function_privilege('taska_tenant_app',function_oid,'EXECUTE')
     OR has_function_privilege('taska_tenant_admin',function_oid,'EXECUTE')
     OR has_function_privilege('taska_tenant_job',function_oid,'EXECUTE')
     OR has_function_privilege('taska_tenant_dispatcher',function_oid,'EXECUTE')
     OR EXISTS (
       SELECT 1 FROM pg_proc p
       CROSS JOIN LATERAL aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) acl
       WHERE p.oid=function_oid AND acl.grantee=0
     ) THEN
    RAISE EXCEPTION 'Tenant list function grants mismatch';
  END IF;
END
$guard$;
