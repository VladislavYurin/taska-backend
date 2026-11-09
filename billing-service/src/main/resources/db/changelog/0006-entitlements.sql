SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.entitlement_snapshots (
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id) ON DELETE RESTRICT,
 version bigint NOT NULL CHECK(version>0),
 plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id) ON DELETE RESTRICT,
 period_id uuid,
 valid_from timestamptz NOT NULL, valid_until timestamptz,
 paid_through timestamptz,
 max_projects bigint NOT NULL CHECK(max_projects>0),
 max_members bigint NOT NULL CHECK(max_members>0),
 max_tasks_per_project bigint CHECK(max_tasks_per_project>=0),
 max_storage_bytes bigint NOT NULL CHECK(max_storage_bytes>=0),
 features jsonb NOT NULL CHECK(jsonb_typeof(features)='array'),
 restrictions jsonb NOT NULL CHECK(jsonb_typeof(restrictions)='array'),
 coverage_state text NOT NULL CHECK(coverage_state IN ('FREE','PAID','UNPAID','DISPUTED','REVOKED')),
 state_key text NOT NULL,
 source_operation_id uuid NOT NULL,
 created_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,version),
 UNIQUE(tenant_id,source_operation_id),
 FOREIGN KEY(tenant_id,period_id) REFERENCES taska.subscription_periods(tenant_id,id) ON DELETE RESTRICT,
 CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE FUNCTION taska.protect_entitlement_snapshot() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 RAISE EXCEPTION 'Immutable entitlement snapshot' USING ERRCODE='23514';
END $$;
CREATE TRIGGER immutable_entitlement_snapshot BEFORE UPDATE OR DELETE ON taska.entitlement_snapshots
 FOR EACH ROW EXECUTE FUNCTION taska.protect_entitlement_snapshot();
