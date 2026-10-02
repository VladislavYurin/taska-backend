--liquibase formatted sql

-- changeset taska:0003-user-status-locked
-- comment: Добавление статуса LOCKED в users_status_chk
ALTER TABLE taska.users DROP CONSTRAINT users_status_chk;

ALTER TABLE taska.users ADD CONSTRAINT users_status_chk
    CHECK (status IN ('INVITED', 'ACTIVE', 'BLOCKED', 'LOCKED'));