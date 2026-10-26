--liquibase formatted sql
-- changeset taska:0006-signup-email-kind
ALTER TABLE taska.invitation_email_deliveries ADD COLUMN message_kind text NOT NULL DEFAULT 'INVITATION'
  CHECK (message_kind IN ('INVITATION','SIGNUP','EXISTING_ACCOUNT'));
