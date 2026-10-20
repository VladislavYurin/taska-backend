package ru.taska.domain;

import ru.taska.domain.dto.BoardIssueDto;

import java.util.List;
import java.util.UUID;

/**
 * Внутренняя модель задачи для формирования Board API.
 *
 * @param statusKey ключ статуса задачи, используемый для группировки по колонкам
 * @param labelIds идентификаторы меток задачи, используемые для обогащения {@code issue.labels}
 *                 объектами {id, name, color} на этапе построения доски
 * @param issue REST DTO задачи доски
 */
public record BoardIssueData(
        String statusKey,
        List<UUID> labelIds,
        BoardIssueDto issue
) {
}
