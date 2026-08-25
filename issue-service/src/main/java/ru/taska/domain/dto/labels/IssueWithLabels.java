package ru.taska.domain.dto.labels;

import ru.taska.domain.Issue;
import ru.taska.domain.labels.ProjectLabels;

import java.util.List;

/**
 * Представление задачи со всеми метками. Используется для возвращения списка задач со всеми метками
 * @param issue - задача
 * @param labels - список меток задачи
 */
public record IssueWithLabels(Issue issue, List<ProjectLabels> labels) {
}
