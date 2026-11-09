-- Run as the owning service migrator, then protect/audit both tables with tenant_id guards.
CREATE TABLE taska.entitlement_projections (
  tenant_id uuid PRIMARY KEY,
  version bigint NOT NULL CHECK(version>0),
  body text NOT NULL,
  observed_at timestamptz NOT NULL
);
CREATE TABLE taska.entitlement_projection_events (
  tenant_id uuid NOT NULL,
  event_id uuid NOT NULL,
  body text NOT NULL,
  PRIMARY KEY(tenant_id,event_id)
);
