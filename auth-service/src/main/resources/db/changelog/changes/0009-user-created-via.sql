--liquibase formatted sql

-- changeset taska:0009-user-created-via
ALTER TABLE taska.users ADD COLUMN created_via varchar(16) NOT NULL DEFAULT 'INVITATION';
ALTER TABLE taska.users ADD CONSTRAINT users_created_via_chk CHECK (created_via IN ('INVITATION','SIGNUP','OAUTH'));
