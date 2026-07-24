--liquibase formatted sql

-- changeset taska:0001-modify-notifications-type-check
-- comment: Добавление новых значений типов уведомлений для таблицы taska.notifications

ALTER TABLE taska.notifications
    DROP CONSTRAINT IF EXISTS notifications_type_chk;

ALTER TABLE taska.notifications
    ADD CONSTRAINT notifications_type_chk
        CHECK (notification_type IN (
                                      'ISSUE_ASSIGNED', 'ISSUE_TRANSITIONED',
                                      'ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_DELETED',
                                      'ISSUE_LINK_CREATED', 'ISSUE_LINK_DELETED',
                                      'USER_INVITED', 'USER_ACTIVATED',
                                      'MEMBER_REMOVED', 'MEMBER_ADDED', 'MEMBER_UPDATED',
                                      'PROJECT_CREATED'
            )
        );
