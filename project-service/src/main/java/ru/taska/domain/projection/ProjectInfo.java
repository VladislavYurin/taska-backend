package ru.taska.domain.projection;

import java.util.UUID;

/**
 * Проекция с основной информацией о проекте.
 *
 * @param id идентификатор проекта
 * @param key ключ проекта
 * @param name название проекта
 */
public record ProjectInfo(
        UUID id,
        String key,
        String name
) {}
