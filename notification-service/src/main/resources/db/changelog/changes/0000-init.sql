--liquibase formatted sql

-- changeset taska:0000-create-schema
-- comment: Создание схемы базы данных.
CREATE SCHEMA IF NOT EXISTS taska;

-- changeset taska:0001-init-extensions
-- comment: Подключение необходимого расширения.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- changeset taska:0002-create-notifications
-- comment: Создание таблицы notifications.
CREATE TABLE IF NOT EXISTS taska.notifications (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL,
    notification_type TEXT        NOT NULL,
    title             TEXT        NOT NULL,
    body              TEXT,
    link              TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at           TIMESTAMPTZ,
    source_event_id   UUID        NOT NULL,

    CONSTRAINT notifications_type_chk
        CHECK (notification_type IN ('ISSUE_ASSIGNED', 'ISSUE_TRANSITIONED', 'ISSUE_CREATED', 'USER_INVITED', 'MEMBER_REMOVED', 'MEMBER_ADDED', 'PROJECT_CREATED'))
);

-- changeset taska:0003-notifications-indexes
-- comment: Индексы для ускорения частых операций.
CREATE INDEX idx_notifications_user_created ON taska.notifications (user_id, created_at);
CREATE INDEX idx_notifications_user_unread ON taska.notifications (user_id, read_at) WHERE read_at IS NULL;

-- changeset taska:0004-create-notification-preferences
-- comment: Создание таблицы notification_preferences.
CREATE TABLE IF NOT EXISTS taska.notification_preferences (
    user_id        UUID        PRIMARY KEY,
    in_app_enabled BOOLEAN     NOT NULL    DEFAULT TRUE,
    email_enabled  BOOLEAN     NOT NULL    DEFAULT TRUE,
    updated_at     TIMESTAMPTZ NOT NULL    DEFAULT NOW()
);

-- changeset taska:0005-create-processed-events
-- comment: Создание таблицы processed_events.
CREATE TABLE taska.processed_events (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL    DEFAULT NOW(),
    source_type  TEXT        NOT NULL,

    CONSTRAINT processed_events_event_id_unq UNIQUE (event_id)
);

-- changeset taska:0006-create-email-delivery-attempts
-- comment: Создание таблицы email_delivery_attempts.
CREATE TABLE taska.email_delivery_attempts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID        NOT NULL,
    to_email        TEXT        NOT NULL,
    subject         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_retry_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT email_delivery_attempts_type_chk CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    CONSTRAINT fk_email_attempts_notification
        FOREIGN KEY (notification_id) REFERENCES taska.notifications (id)
            ON DELETE CASCADE
);

-- changeset taska:0007-email-delivery-attempts-indexes
-- comment: Индексы для ускорения частых операций.
CREATE INDEX idx_email_attempts_status_next_retry ON taska.email_delivery_attempts (status, next_retry_at);
CREATE INDEX idx_email_attempts_notification ON taska.email_delivery_attempts (notification_id);