--liquibase formatted sql
-- changeset taska:0009-notification-channels
ALTER TABLE taska.notification_preferences
    ADD COLUMN email_version uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN telegram_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN telegram_chat_id bigint UNIQUE,
    ADD COLUMN telegram_user_id bigint UNIQUE,
    ADD COLUMN telegram_username text,
    ADD COLUMN telegram_name text,
    ADD COLUMN telegram_bot_username text,
    ADD COLUMN telegram_version uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN telegram_error text;

CREATE TABLE taska.telegram_link_requests (
    user_id uuid PRIMARY KEY REFERENCES taska.notification_preferences(user_id),
    link_id uuid NOT NULL UNIQUE,
    token_hash text NOT NULL UNIQUE,
    bot_username text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    candidate_chat_id bigint,
    candidate_user_id bigint,
    candidate_username text,
    candidate_name text
);

CREATE TABLE taska.telegram_updates (
    bot_username text NOT NULL,
    update_id bigint NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(bot_username, update_id)
);

CREATE TABLE taska.notification_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dedup_key text NOT NULL UNIQUE,
    notification_id uuid REFERENCES taska.notifications(id) ON DELETE CASCADE,
    user_id uuid,
    channel text NOT NULL CHECK (channel IN ('EMAIL','TELEGRAM')),
    channel_version uuid,
    reply_chat_id bigint,
    reply_text text,
    bot_username text,
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','RETRY','SENT','FAILED','CANCELLED')),
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    lease_id uuid,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(notification_id, channel),
    CHECK ((notification_id IS NOT NULL AND user_id IS NOT NULL AND channel_version IS NOT NULL
            AND reply_chat_id IS NULL AND reply_text IS NULL)
        OR (notification_id IS NULL AND channel = 'TELEGRAM' AND reply_chat_id IS NOT NULL AND reply_text IS NOT NULL))
);
CREATE INDEX notification_deliveries_due_idx ON taska.notification_deliveries(channel, status, next_attempt_at);

CREATE TABLE taska.notification_channel_throttle (
    channel text PRIMARY KEY,
    next_send_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO taska.notification_channel_throttle(channel) VALUES ('TELEGRAM');
