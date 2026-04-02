--liquibase formatted sql

--changeset taska:notifications-enums dbms:postgresql splitStatements:false
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'notification_type') THEN
    CREATE TYPE notification_type AS ENUM (
      'ISSUE_ASSIGNED',
      'ISSUE_TRANSITIONED',
      'ISSUE_CREATED',
      'USER_INVITED'
    );
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'email_attempt_status') THEN
    CREATE TYPE email_attempt_status AS ENUM ('PENDING', 'SENT', 'FAILED');
  END IF;
END $$;

--changeset taska:notifications-tables-v1 dbms:postgresql splitStatements:false
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'notifications') THEN
    CREATE TABLE notifications (
      id                UUID PRIMARY KEY  NOT NULL,
      user_id           UUID              NOT NULL,
      notification_type notification_type NOT NULL,
      title             TEXT              NOT NULL,
      body              TEXT,
      link              TEXT,
      created_at        TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
      read_at           TIMESTAMPTZ,
      source_event_id   UUID              NOT NULL
    );
    CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at);
    CREATE INDEX idx_notifications_user_unread ON notifications (user_id, read_at)
      WHERE read_at IS NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'notification_preferences') THEN
    CREATE TABLE notification_preferences (
      user_id        UUID PRIMARY KEY NOT NULL,
      in_app_enabled BOOLEAN          NOT NULL DEFAULT TRUE,
      email_enabled  BOOLEAN          NOT NULL DEFAULT TRUE,
      updated_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW()
    );
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'processed_events') THEN
    CREATE TABLE processed_events (
      event_id     TEXT             PRIMARY KEY NOT NULL,
      processed_at TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
      source_type  TEXT             NOT NULL
    );
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'email_delivery_attempts') THEN
    CREATE TABLE email_delivery_attempts (
      id              UUID PRIMARY KEY     NOT NULL,
      notification_id UUID                 NOT NULL,
      to_email        TEXT                 NOT NULL,
      subject         TEXT                 NOT NULL,
      status          email_attempt_status NOT NULL DEFAULT 'PENDING',
      attempts        INT                  NOT NULL DEFAULT 0,
      last_error      TEXT,
      next_retry_at   TIMESTAMPTZ,
      created_at      TIMESTAMPTZ          NOT NULL DEFAULT NOW(),
      updated_at      TIMESTAMPTZ          NOT NULL DEFAULT NOW()
    );
    ALTER TABLE email_delivery_attempts
      ADD CONSTRAINT fk_email_attempts_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (id)
        ON DELETE CASCADE;
    CREATE INDEX idx_email_attempts_status_next_retry ON email_delivery_attempts (status, next_retry_at);
    CREATE INDEX idx_email_attempts_notification      ON email_delivery_attempts (notification_id);
  END IF;
END $$;