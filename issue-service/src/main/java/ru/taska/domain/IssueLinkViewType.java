package ru.taska.domain;

/**
 * Тип связи между задачами для отображения при чтении (включает в себя направленные и обратные связи).
 */
public enum IssueLinkViewType {
    RELATES_TO,
    BLOCKS,
    IS_BLOCKED_BY,
    DUPLICATES,
    IS_DUPLICATED_BY
}
