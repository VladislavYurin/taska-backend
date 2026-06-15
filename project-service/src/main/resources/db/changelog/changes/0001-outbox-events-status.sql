--liquibase formatted sql

-- changeset taska:0001-outbox-events-status
-- comment: Добавление статуса событий outbox.

ALTER TABLE taska.outbox_events
    ADD COLUMN status text;

UPDATE taska.outbox_events
SET status =
        CASE
            WHEN published_at IS NULL THEN 'NEW'
            ELSE 'PUBLISHED'
        END;

ALTER TABLE taska.outbox_events
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE taska.outbox_events
    ALTER COLUMN status SET DEFAULT 'NEW';

ALTER TABLE taska.outbox_events
    ADD CONSTRAINT outbox_events_status_chk CHECK (status IN ('NEW', 'PROCESSING', 'PUBLISHED', 'FAILED'));
