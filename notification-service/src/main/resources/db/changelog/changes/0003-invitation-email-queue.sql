--liquibase formatted sql
-- changeset taska:0003-invitation-email-queue
CREATE TABLE taska.invitation_email_deliveries (
    user_id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    email text NOT NULL,
    token text,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status text NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    lease_id uuid,
    CONSTRAINT invitation_email_status_chk CHECK (status IN ('PENDING','PROCESSING','RETRY','SENT','EXPIRED','FAILED'))
);
CREATE INDEX invitation_email_due_idx ON taska.invitation_email_deliveries (status, next_attempt_at);
