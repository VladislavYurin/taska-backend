SET LOCAL ROLE taska_tenant_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.tenant_restrictions(
 tenant_id uuid NOT NULL REFERENCES taska.tenants(id),kind text NOT NULL,
 source_id uuid NOT NULL,active boolean NOT NULL,version bigint NOT NULL CHECK(version>0),
 reason_code text NOT NULL,created_by uuid,created_at timestamptz NOT NULL,resolved_at timestamptz,
 PRIMARY KEY(tenant_id,kind,source_id),
 CHECK(kind IN ('OWNER_PAUSE','PLATFORM_BLOCK','NONPAYMENT','QUOTA_EXCEEDED','FINANCIAL_DISPUTE')),
 CHECK((active AND resolved_at IS NULL) OR (NOT active AND resolved_at IS NOT NULL))
);
CREATE INDEX tenant_restrictions_active_idx ON taska.tenant_restrictions(tenant_id,active,kind);
CREATE TABLE taska.tenant_restriction_audit(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES taska.tenants(id),actor_id uuid NOT NULL,
 action text NOT NULL,administrative boolean NOT NULL,reason text NOT NULL,
 result text NOT NULL CHECK(result IN ('SUCCEEDED','NO_CHANGE')),
 tenant_version bigint NOT NULL,restriction_version bigint NOT NULL,operation_id uuid NOT NULL,
 created_at timestamptz NOT NULL,UNIQUE(tenant_id,operation_id)
);
