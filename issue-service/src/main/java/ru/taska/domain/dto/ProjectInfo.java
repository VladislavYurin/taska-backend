package ru.taska.domain.dto;

/**
 * Класс с основной информацией о проекте.
 *
 * @param id идентификатор проекта
 * @param key ключ проекта
 * @param name название проекта
 */
public record ProjectInfo(
        String id,
        String key,
        String name
) {}
