SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.renewal_calendar_assignments (
 tenant_id uuid NOT NULL REFERENCES taska.subscriptions(tenant_id),
 id uuid NOT NULL,
 source_period_id uuid NOT NULL,
 plan_version_id uuid NOT NULL REFERENCES taska.catalog_plan_versions(id),
 purpose text NOT NULL CHECK(purpose='RENEWAL'),
 period_starts_at timestamptz NOT NULL,
 period_ends_at timestamptz NOT NULL CHECK(period_ends_at>period_starts_at),
 anchor_day smallint NOT NULL CHECK(anchor_day BETWEEN 1 AND 31),
 anchor_time_utc time NOT NULL,
 created_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,period_starts_at,purpose),
 FOREIGN KEY(tenant_id,source_period_id) REFERENCES taska.subscription_periods(tenant_id,id)
);
CREATE FUNCTION taska.protect_renewal_assignment() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable renewal assignment' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_renewal_assignment BEFORE UPDATE OR DELETE ON taska.renewal_calendar_assignments
 FOR EACH ROW EXECUTE FUNCTION taska.protect_renewal_assignment();
