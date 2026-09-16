--liquibase formatted sql

-- changeset taska:0004-add-comment-notification-types
-- comment: Добавление типов уведомлений для комментариев (ISSUE_COMMENT_CREATED)

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
                                     'PROJECT_CREATED',
                                     'LABEL_ADDED', 'LABEL_REMOVED',
                                     'USER_BLOCKED', 'USER_UNBLOCKED',
                                     'ISSUE_COMMENT_CREATED'
            ));

CREATE UNIQUE INDEX IF NOT EXISTS notifications_source_event_user_uniq
    ON taska.notifications (source_event_id, user_id);