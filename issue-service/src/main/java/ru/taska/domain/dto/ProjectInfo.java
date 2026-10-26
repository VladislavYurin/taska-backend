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
        String name,
        java.util.Map<String, IssueTypeMetadata> issueTypes
) {
    public ProjectInfo(String id, String key, String name) { this(id, key, name, java.util.Map.of()); }
}
