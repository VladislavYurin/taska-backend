--liquibase formatted sql

--changeset taska:0010-open-issue-types
ALTER TABLE taska.issues DROP CONSTRAINT issues_type_chk;
ALTER TABLE taska.issues ADD CONSTRAINT issues_type_key_chk
    CHECK (issue_type ~ '^[A-Z][A-Z0-9_]{1,31}$');
-- Rollback is intentionally rejected while custom types exist; never discard their issues.
--rollback ALTER TABLE taska.issues ADD CONSTRAINT issues_type_chk CHECK (issue_type IN ('TASK', 'BUG', 'STORY'));
--rollback ALTER TABLE taska.issues DROP CONSTRAINT issues_type_key_chk;
