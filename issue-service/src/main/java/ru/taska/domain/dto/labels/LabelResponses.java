package ru.taska.domain.dto.labels;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO ответов для работы с метками.
 */
public interface LabelResponses {

    /**
     * Информация о метке проекта
     * Используется для:
     * - CreateProjectLabel → ProjectLabelResponse
     * - UpdateProjectLabel → ProjectLabelResponse
     * - ListProjectLabels → ProjectLabelResponse (в списке меток)
     * - ListIssueLabels → ProjectLabelResponse (в списке меток)
     */
    record ProjectLabelInfo(
            UUID id,
            UUID projectId,
            String name,
            String color,
            UUID createdBy,
            Instant createdAt,
            Instant deletedAt
    ) {}

    /// ===== Взаимодействие с PROJECT =====

    /**
     * Ответ на удаление метки проекта (ADMIN)
     */
    record DeleteProjectLabelResponseDto(
            UUID labelId,
            UUID projectId,
            Instant deletedAt
    ) {
        public static DeleteProjectLabelResponseDto of(UUID labelId, UUID projectId) {
            return new DeleteProjectLabelResponseDto(labelId, projectId, Instant.now());
        }
    }

    /**
     * Список меток проекта
     */
    record ListProjectLabelResponseDto(
            List<ProjectLabelInfo> labels,
            int totalCount
    ) {
        public static ListProjectLabelResponseDto of(List<ProjectLabelInfo> labels) {
            return new ListProjectLabelResponseDto(labels, labels.size());
        }
    }

    /// ===== Взаимодействие с ISSUE =====

    /**
     * Ответ на добавления метки к задаче
     */
    record AddIssueLabelResponseDto(
            UUID issueId,
            UUID labelId,
            UUID createdBy,
            Instant createdAt
    ) {
        public static AddIssueLabelResponseDto of(UUID issueId, UUID labelId, UUID userId) {
            return new AddIssueLabelResponseDto(issueId, labelId, userId, Instant.now());
        }
    }

    /**
     * Результат операции удаления метки с задачи
     */
    record RemoveIssueLabelResponseDto(
            UUID issueId,
            UUID labelId
    ) {
        public static RemoveIssueLabelResponseDto of(UUID issueId, UUID labelId) {
            return new RemoveIssueLabelResponseDto(issueId, labelId);
        }
    }

    /**
     * Список меток задачи
     */
    record ListIssueLabelResponseDto(
            List<ProjectLabelInfo> labels
    ) {
        public static ListIssueLabelResponseDto of(List<ProjectLabelInfo> labels) {
            return new ListIssueLabelResponseDto(labels);
        }
    }

}
