package ru.taska.domain;

/**
 * Тип события в истории изменений задачи.
 */
public enum IssueEventType {
    CREATED,
    UPDATED,
    ASSIGNED,
    TRANSITIONED,
    DELETED
}
