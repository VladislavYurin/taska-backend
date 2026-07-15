package ru.taska.domain;

/**
 * Тип события в истории изменений задачи.
 */
public enum IssueEventType {
    CREATED,
    UPDATED,
    ASSIGNED,
    TRANSITIONED,
    DELETED,
    ATTACHMENT_UPLOADED,
    ATTACHMENT_DELETED,
    COMMENT_CREATED,
    COMMENT_UPDATED,
    COMMENT_DELETED
}
