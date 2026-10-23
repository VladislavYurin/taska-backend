package ru.taska.domain.dto.labels;

import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.ProjectLabels;

import java.util.List;

/**
 * Представление задачи со всеми метками. Используется для возвращения списка задач со всеми метками
 * @param issue - задача
 * @param labels - список меток задачи
 */
public record IssueWithLabels(Issue issue, List<ProjectLabels> labels) {
}
