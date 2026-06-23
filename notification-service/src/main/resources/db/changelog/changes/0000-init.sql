--liquibase formatted sql

-- changeset taska:0000-initial
-- comment: Инициализация миграций notification-service

CREATE SCHEMA IF NOT EXISTS taska;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Notifications
CREATE TABLE IF NOT EXISTS taska.notifications
(
    id                uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id           uuid        NOT NULL,
    notification_type text        NOT NULL,
    title             text        NOT NULL,
    body              text        NULL,
    link              text        NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    read_at           timestamptz NULL,
    source_event_id   uuid        NOT NULL,

    CONSTRAINT notifications_type_chk CHECK (
        notification_type IN (
                              'ISSUE_ASSIGNED', 'ISSUE_TRANSITIONED',
                              'ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_DELETED',
                              'USER_INVITED', 'MEMBER_REMOVED', 'MEMBER_ADDED',
                              'PROJECT_CREATED', 'MEMBER_ROLE_CHANGED'
            )
        )
);

CREATE INDEX IF NOT EXISTS notifications_user_created_idx
    ON taska.notifications (user_id, created_at);

CREATE INDEX IF NOT EXISTS notifications_user_unread_idx
    ON taska.notifications (user_id, read_at)
    WHERE read_at IS NULL;

-- Notification Preferences
CREATE TABLE IF NOT EXISTS taska.notification_preferences
(
    user_id        uuid PRIMARY KEY,
    email          text,
    in_app_enabled boolean     NOT NULL DEFAULT true,
    email_enabled  boolean     NOT NULL DEFAULT true,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- Processed Events
CREATE TABLE IF NOT EXISTS taska.processed_events
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    event_id     text        NOT NULL UNIQUE,
    processed_at timestamptz NOT NULL DEFAULT now(),
    source_type  text        NOT NULL
);

-- Email Delivery Attempts
CREATE TABLE IF NOT EXISTS taska.email_delivery_attempts
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    notification_id uuid        NOT NULL REFERENCES taska.notifications (id) ON DELETE CASCADE,
    to_email        text        NOT NULL,
    subject         text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        int         NOT NULL DEFAULT 0,
    last_error      text        NULL,
    next_retry_at   timestamptz NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT email_delivery_attempts_status_chk CHECK
        (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS email_attempts_status_next_retry_idx
    ON taska.email_delivery_attempts (status, next_retry_at);

CREATE INDEX IF NOT EXISTS email_attempts_notification_idx
    ON taska.email_delivery_attempts (notification_id);