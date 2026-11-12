--liquibase formatted sql
-- changeset taska:0016-tenant-oauth-grants
-- comment: TAS-498 tenant-bound OAuth consent; the grant itself is a USER/ONE_TENANT integration grant.

CREATE TABLE taska.oauth_grant_approvals (
    grant_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    client_id varchar(2048) NOT NULL,
    authorization_request_sha256 bytea NOT NULL CHECK (length(authorization_request_sha256) = 32),
    idempotency_key uuid NOT NULL,
    request_sha256 bytea NOT NULL CHECK (length(request_sha256) = 32),
    created_at timestamptz NOT NULL,
    claim_expires_at timestamptz NOT NULL,
    claimed_at timestamptz,
    CONSTRAINT oauth_grant_approvals_grant_fk FOREIGN KEY (grant_id,user_id)
        REFERENCES taska.integration_grants(id,user_id) ON DELETE RESTRICT,
    CONSTRAINT oauth_grant_approvals_request_unique UNIQUE (user_id,authorization_request_sha256),
    CONSTRAINT oauth_grant_approvals_key_unique UNIQUE (user_id,idempotency_key),
    CONSTRAINT oauth_grant_approvals_claim_window_chk CHECK (claim_expires_at > created_at),
    CONSTRAINT oauth_grant_approvals_claim_chk CHECK (claimed_at IS NULL OR claimed_at >= created_at)
);
