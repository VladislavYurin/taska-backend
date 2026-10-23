--liquibase formatted sql

-- changeset taska:0003-audit-actor-identity
-- comment: Preserve historical actor labels and store name/email snapshots for new entries.
ALTER TABLE taska.admin_audit_log RENAME COLUMN actor_login TO actor_display_name;
ALTER TABLE taska.admin_audit_log ALTER COLUMN actor_display_name TYPE text;
ALTER TABLE taska.admin_audit_log ADD COLUMN actor_email varchar(320);
