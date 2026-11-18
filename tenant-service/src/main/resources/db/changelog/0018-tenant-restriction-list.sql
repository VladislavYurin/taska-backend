SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE POLICY tenant_list_actor_restrictions_guard ON taska.tenant_restrictions
 FOR SELECT TO taska_tenant_migrator USING(tenant_id IN (
 SELECT m.tenant_id FROM taska.tenant_memberships m
 WHERE m.user_id=NULLIF(current_setting('app.tenant_list_actor_id',true),'')::uuid AND m.status='ACTIVE'));

DROP FUNCTION taska.list_tenants_for_actor(uuid,timestamptz,uuid,integer);

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
  pending_first_payment boolean,
  actor_membership_role text,
  actor_membership_status text,
  actor_membership_version bigint,
  independent_restrictions text[],
  restriction_version bigint
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
      (t.creation_kind='PAID' AND t.first_payment_fact_id IS NULL),
      m.role,m.status,m.version,
      ARRAY(SELECT r.kind FROM taska.tenant_restrictions r WHERE r.tenant_id=t.id AND r.active ORDER BY r.kind,r.source_id),
      t.restriction_version
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
BEGIN
  IF current_user<>'taska_tenant_migrator' THEN
    RAISE EXCEPTION 'Tenant migration owner role required';
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

  IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname='tenant_list_actor_membership_guard'
       AND polrelid='taska.tenant_memberships'::regclass)
     OR NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname='tenant_list_actor_registry_guard'
       AND polrelid='taska.tenants'::regclass) THEN
    RAISE EXCEPTION 'Tenant list RLS policies missing';
  END IF;
END
$guard$;
