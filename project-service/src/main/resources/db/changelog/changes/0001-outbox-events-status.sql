--liquibase formatted sql

-- changeset taska:0001-outbox-events-status
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

-- changeset taska:0001b-outbox-events-status-chk splitStatements:false
-- comment: Добавление CHECK-constraint outbox_events_status_chk на колонку status таблицы outbox_events.
--          Используется DO-блок с явной проверкой pg_constraint, потому что PostgreSQL не поддерживает
--          ADD CONSTRAINT IF NOT EXISTS. Условие: если constraint с именем outbox_events_status_chk
--          ещё не существует для таблицы taska.outbox_events — создаём его.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'outbox_events_status_chk'
          AND conrelid = 'taska.outbox_events'::regclass
    ) THEN
        ALTER TABLE taska.outbox_events
            ADD CONSTRAINT outbox_events_status_chk CHECK (status IN ('NEW', 'PROCESSING', 'PUBLISHED', 'FAILED'));
    END IF;
END;
$$
