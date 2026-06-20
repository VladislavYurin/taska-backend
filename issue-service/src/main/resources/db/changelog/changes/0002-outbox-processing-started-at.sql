--liquibase formatted sql

-- changeset taska:0014-outbox-processing-started-at
-- comment: Добавление processing_started_at для recovery outbox событий

ALTER TABLE taska.outbox_events
    ADD COLUMN IF NOT EXISTS processing_started_at timestamptz NULL;


CREATE INDEX IF NOT EXISTS outbox_events_processing_idx
    ON taska.outbox_events(status, processing_started_at);