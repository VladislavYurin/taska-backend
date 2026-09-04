--liquibase formatted sql

-- changeset taska:0007-issue-planing-fields
-- comment: Добавление полей для планирования в таблицу issues

ALTER TABLE taska.issues
    ADD COLUMN IF NOT EXISTS story_points               numeric(5, 2) NULL,
    ADD COLUMN IF NOT EXISTS start_date                 date          NULL,
    ADD COLUMN IF NOT EXISTS due_date                   date          NULL,
    ADD COLUMN IF NOT EXISTS original_estimate_minutes  integer       NULL,
    ADD COLUMN IF NOT EXISTS remaining_estimate_minutes integer       NULL;

ALTER TABLE taska.issues
DROP CONSTRAINT IF EXISTS issues_story_points_chk;

ALTER TABLE taska.issues
    ADD CONSTRAINT issues_story_points_chk
        CHECK (story_points IS NULL OR story_points >= 0);

ALTER TABLE taska.issues
DROP CONSTRAINT IF EXISTS issues_original_estimate_chk;

ALTER TABLE taska.issues
    ADD CONSTRAINT issues_original_estimate_chk
        CHECK (original_estimate_minutes IS NULL OR original_estimate_minutes >= 0);

ALTER TABLE taska.issues
DROP CONSTRAINT IF EXISTS issues_remaining_estimate_chk;

ALTER TABLE taska.issues
    ADD CONSTRAINT issues_remaining_estimate_chk
        CHECK (remaining_estimate_minutes IS NULL OR remaining_estimate_minutes >= 0);

ALTER TABLE taska.issues
DROP CONSTRAINT IF EXISTS issues_dates_chk;

ALTER TABLE taska.issues
    ADD CONSTRAINT issues_dates_chk
        CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date);