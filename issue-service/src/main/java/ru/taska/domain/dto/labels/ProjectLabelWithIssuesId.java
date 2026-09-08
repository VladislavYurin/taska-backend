package ru.taska.domain.dto.labels;

import ru.taska.domain.labels.ProjectLabels;

import java.time.Instant;
import java.util.UUID;

/**
 * Представление меток проекта с айди задачи для группировки меток по задачам
 * @param id - aйди метки
 * @param projectId - айди проекта
 * @param name - название метки
 * @param color - цвет метки в HEX формате
 * @param createdBy - айди создателя метки
 * @param createdAt - время создания метки
 * @param deletedAt - время удаления метки
 * @param issueId - айди задачи
 */
public record ProjectLabelWithIssuesId(
        UUID id,
        UUID projectId,
        String name,
        String color,
        UUID createdBy,
        Instant createdAt,
        Instant deletedAt,
        UUID issueId
){
    public ProjectLabels toProjectLabels() {
        return ProjectLabels.builder()
                .id(id)
                .projectId(projectId)
                .name(name)
                .color(color)
                .createdBy(createdBy)
                .createdAt(createdAt)
                .deletedAt(deletedAt)
                .build();
    }
}
