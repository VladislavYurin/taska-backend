package ru.taska.domain.projection;

import org.springframework.data.relational.core.mapping.Column;

import java.util.UUID;

/**
 * Представление целевой задачи, получаемое при загрузке связей задачи.
 *
 * <p>Содержит только поля, необходимые для отображения целевой задачи
 * в информации о связях. Используется как проекция результата запроса
 * к базе данных.</p>
 */
public record TargetIssue(
        @Column("id")
        UUID id,
        String issueKey,
        String summary,
        @Column("project_id")
        UUID projectId,
        String statusKey
) {}
