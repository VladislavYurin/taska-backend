-- liquibase formatted sql

-- changeset taska:0013-outbox-events-status
-- comment: Добавление статуса событий outbox.

ALTER TABLE taska.outbox_events
    ADD COLUMN IF NOT EXISTS status text;

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

-- changeset taska:0014-outbox-processing-started-at
-- comment: Добавление processing_started_at для recovery outbox событий

ALTER TABLE taska.outbox_events
    ADD COLUMN IF NOT EXISTS processing_started_at timestamptz NULL;

-- changeset taska:0015-outbox-events-indexes
-- comment: Создание индексов для оптимизации outbox механизма

CREATE INDEX IF NOT EXISTS outbox_events_status_created_idx
    ON taska.outbox_events(status, created_at)
    WHERE status IN ('NEW', 'PROCESSING');

CREATE INDEX IF NOT EXISTS outbox_events_processing_idx
    ON taska.outbox_events(status, processing_started_at)
    WHERE status = 'PROCESSING' AND processing_started_at IS NOT NULL;