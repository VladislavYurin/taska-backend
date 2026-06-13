--liquibase formatted sql

--changeset taska:notification-type-project-events dbms:postgresql runInTransaction:false
-- comment: Добавление типов уведомлений для событий проектов.
-- ALTER TYPE ... ADD VALUE нельзя выполнять внутри транзакции, поэтому runInTransaction:false.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'PROJECT_CREATED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'MEMBER_ADDED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'MEMBER_ROLE_CHANGED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'MEMBER_REMOVED';
