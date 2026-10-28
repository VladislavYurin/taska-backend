--liquibase formatted sql
-- changeset taska:0012-oauth-sign-in-target
ALTER TABLE taska.oauth_states ADD COLUMN target varchar(8) NOT NULL DEFAULT 'ui', ADD COLUMN binding char(43);
ALTER TABLE taska.oauth_states ADD CONSTRAINT oauth_state_target_binding CHECK (
 (target='ui' AND binding IS NULL) OR (target='oauth' AND binding IS NOT NULL AND binding ~ '^[A-Za-z0-9_-]{43}$')
);
ALTER TABLE taska.oauth_grants ADD COLUMN target varchar(8) NOT NULL DEFAULT 'ui', ADD COLUMN binding char(43);
ALTER TABLE taska.oauth_grants ADD CONSTRAINT oauth_grant_target_binding CHECK (
 (target='ui' AND binding IS NULL) OR (target='oauth' AND kind='EXCHANGE' AND binding IS NOT NULL AND binding ~ '^[A-Za-z0-9_-]{43}$')
);
