package ru.taska.domain.entity;

/**
 * Направленный тип связи между задачами, хранящийся в БД.
 */
public enum IssueLinkType {
    BLOCKS,
    RELATES_TO,
    DUPLICATES
}
