package ru.taska.domain;

import ru.taska.domain.dto.BoardIssueDto;

/**
 * Внутренняя модель задачи для формирования Board API.
 *
 * @param statusKey ключ статуса задачи, используемый для группировки по колонкам
 * @param issue REST DTO задачи доски
 */
public record BoardIssueData(
        String statusKey,
        BoardIssueDto issue
) {
}