--liquibase formatted sql

--changeset taska:0001-add-email-to-notification-preferences
--comment: Добавление email-адреса получателя для email-уведомлений.
ALTER TABLE taska.notification_preferences
    ADD COLUMN IF NOT EXISTS email TEXT;