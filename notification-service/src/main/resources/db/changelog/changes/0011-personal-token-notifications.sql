--liquibase formatted sql

--changeset taska:0011-personal-token-notifications
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
                                     'PROJECT_CREATED', 'PROJECT_ARCHIVED',
                                     'PERSONAL_ACCESS_TOKEN_CREATED', 'PERSONAL_ACCESS_TOKEN_REVOKED',
                                     'LABEL_ADDED', 'LABEL_REMOVED',
                                     'USER_BLOCKED', 'USER_UNBLOCKED',
                                     'ISSUE_COMMENT_CREATED', 'ISSUE_COMMENT_UPDATED', 'ISSUE_COMMENT_DELETED',
                                     'ISSUE_ATTACHMENT_ADDED', 'ISSUE_ATTACHMENT_DELETED'
            ));
