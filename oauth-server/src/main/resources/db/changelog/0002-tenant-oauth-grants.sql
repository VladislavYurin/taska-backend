--liquibase formatted sql
--changeset taska:oauth-0002
-- TAS-498: the authoritative grant is auth-service.integration_grants, verified by RPC (never an SQL FK).
-- Legacy rows stay unbound until the clean-start cutover; tenant mode refuses them.
ALTER TABLE oauth2_authorization
    ADD COLUMN integration_grant_id uuid,
    ADD COLUMN tenant_id uuid,
    ADD COLUMN grant_version bigint;
ALTER TABLE oauth2_authorization ADD CONSTRAINT oauth_authorization_tenant_grant_shape CHECK (
    (integration_grant_id IS NULL AND tenant_id IS NULL AND grant_version IS NULL)
    OR (integration_grant_id IS NOT NULL AND tenant_id IS NOT NULL AND grant_version > 0));
CREATE UNIQUE INDEX oauth_authorization_integration_grant_idx ON oauth2_authorization(integration_grant_id)
    WHERE integration_grant_id IS NOT NULL;
CREATE INDEX oauth_authorization_owner_tenant_idx ON oauth2_authorization(principal_name,tenant_id)
    WHERE tenant_id IS NOT NULL;
