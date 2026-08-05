package ru.taska.domain;

/**
 * Направленный тип связи между задачами, хранящийся в БД.
 */
public enum IssueLinkType {
    BLOCKS,
    RELATES_TO,
    DUPLICATES
}
