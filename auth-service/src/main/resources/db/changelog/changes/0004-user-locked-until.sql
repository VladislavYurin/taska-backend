--liquibase formatted sql

-- changeset taska:0004-user-locked-until
-- comment: Перенос locked_until из credentials в users

ALTER TABLE taska.users
    ADD COLUMN IF NOT EXISTS locked_until timestamptz NULL;

-- переносим текущие значения
UPDATE taska.users u
SET locked_until = c.locked_until, updated_at = now()
FROM taska.credentials c
WHERE c.user_id = u.id
  AND c.credential_type = 'PASSWORD'
  AND c.locked_until IS NOT NULL;

ALTER TABLE taska.credentials DROP COLUMN IF EXISTS locked_until;