package ru.taska.domain.dto;

import java.util.UUID;

/**
 * DTO команды для работы с метками.
 * Группировка связанных запросов в одном файле с использованием внутренних Records.
 * Нужна валидация
 */
public interface LabelCommands {

    /**
     * Создание метки проекта (ADMIN)
     */
    record CreateProjectLabelRequestDto(
            UUID projectId,
            String name,
            String color,
            UUID actorUserId
    ) {}

    /**
     * Обновление метки проекта (ADMIN)
     */
    record UpdateProjectLabelRequestDto(
            UUID labelId,
            UUID projectId,
            String name,
            String color,
            UUID actorUserId
    ) {}

    /**
     * Удаление метки проекта (ADMIN)
     */
    record DeleteProjectLabelRequestDto(
            UUID labelId,
            UUID projectId,
            UUID actorUserId
    ) {}

    /**
     * Получение списка меток проекта (VIEWER+)
     */
    record ListProjectLabelsRequestDto(
            UUID projectId,
            UUID actorUserId
    ) {}

    /**
     * Команда добавления метки к задаче (MEMBER+)
     */
    record AddIssueLabelRequestDto(
            UUID issueId,
            UUID labelId,
            UUID actorUserId
    ) {}

    /**
     * Команда удаления метки с задачи (MEMBER+)
     */
    record RemoveIssueLabelRequestDto(
            UUID issueId,
            UUID labelId,
            UUID actorUserId
    ) {}

    /**
     * Команда получения меток задачи (VIEWER+)
     */
    record ListIssueLabelsRequestDto(
            UUID issueId,
            UUID actorUserId
    ) {}
}
