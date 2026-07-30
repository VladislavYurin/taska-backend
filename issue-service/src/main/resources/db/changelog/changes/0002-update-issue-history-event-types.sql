--liquibase formatted sql

-- changeset taska:0002-update-issue-history-event-types
-- comment: Добавление типов событий ATTACHMENT_UPLOADED и ATTACHMENT_DELETED в check constraint таблицы issue_history

ALTER TABLE taska.issue_history
    DROP CONSTRAINT issue_history_event_type_chk;

ALTER TABLE taska.issue_history
    ADD CONSTRAINT issue_history_event_type_chk CHECK
        (event_type IN (
                        'CREATED', 'DELETED', 'UPDATED',
                        'ASSIGNED', 'TRANSITIONED',
                        'ATTACHMENT_UPLOADED', 'ATTACHMENT_DELETED'
            )
        );
