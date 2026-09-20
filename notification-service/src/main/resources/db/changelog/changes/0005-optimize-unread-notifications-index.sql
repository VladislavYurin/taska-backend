--liquibase formatted sql

-- changeset taska:0005-optimize-unread-notifications-index
-- comment: Оптимизация частичного индекса для непрочитанных уведомлений

DROP INDEX IF EXISTS taska.notifications_user_unread_idx;

CREATE INDEX notifications_user_unread_idx
    ON taska.notifications (user_id)
    WHERE read_at IS NULL;