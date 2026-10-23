--liquibase formatted sql
-- changeset taska:0006-directory-email
-- Existing accounts with case-insensitive duplicate addresses must be reconciled before deployment.
CREATE UNIQUE INDEX users_email_canonical_uq ON taska.users (lower(btrim(email)));
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX users_directory_name_trgm_idx ON taska.users USING gin (lower(display_name) gin_trgm_ops);
CREATE INDEX users_directory_email_trgm_idx ON taska.users USING gin (lower(email) gin_trgm_ops);
