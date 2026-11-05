SET LOCAL ROLE taska_tenant_migrator;
CREATE TABLE taska.quota_counters (
  tenant_id uuid NOT NULL REFERENCES taska.tenants(id),
  resource text NOT NULL CHECK(resource IN ('SEAT','PROJECT','STORAGE_BYTES')),
  scope_id uuid NOT NULL CHECK(scope_id=tenant_id),
  used bigint NOT NULL DEFAULT 0 CHECK(used>=0),
  reserved bigint NOT NULL DEFAULT 0 CHECK(reserved>=0),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  entitlement_version bigint NOT NULL DEFAULT 0 CHECK(entitlement_version>=0),
  reconciled_at timestamptz,
  PRIMARY KEY(tenant_id,resource,scope_id)
);
CREATE TABLE taska.quota_reservations (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  resource text NOT NULL,
  scope_id uuid NOT NULL CHECK(scope_id=tenant_id),
  operation_id uuid NOT NULL,
  resource_owner text NOT NULL,
  fence bigint NOT NULL DEFAULT 1 CHECK(fence>0),
  resource_id uuid,
  request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
  amount bigint NOT NULL CHECK(amount>0),
  CHECK(resource<>'SEAT' OR amount=1),
  state text NOT NULL CHECK(state IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED')),
  entitlement_version bigint NOT NULL CHECK(entitlement_version>0),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  committed_at timestamptz,
  released_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,operation_id,resource),
  FOREIGN KEY(tenant_id,resource,scope_id) REFERENCES taska.quota_counters(tenant_id,resource,scope_id)
);
CREATE INDEX quota_reservation_expiry_idx ON taska.quota_reservations(tenant_id,expires_at,id) WHERE state='ACTIVE';
CREATE TABLE taska.quota_effects (
  tenant_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  resource text NOT NULL,
  scope_id uuid NOT NULL CHECK(scope_id=tenant_id),
  effect_kind text NOT NULL CHECK(effect_kind IN ('COMMIT','RELEASE_USAGE')),
  amount bigint NOT NULL CHECK(amount>0),
  resource_id uuid NOT NULL,
  owner_version bigint NOT NULL CHECK(owner_version>0),
  applied_at timestamptz NOT NULL,
  PRIMARY KEY(tenant_id,operation_id,resource,effect_kind),
  FOREIGN KEY(tenant_id,resource,scope_id) REFERENCES taska.quota_counters(tenant_id,resource,scope_id)
);
