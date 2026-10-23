--liquibase formatted sql

-- changeset taska:0007-remove-user-login
-- comment: Email is the sign-in identifier; user UUID remains the stable identity.
ALTER TABLE taska.users DROP COLUMN login;
