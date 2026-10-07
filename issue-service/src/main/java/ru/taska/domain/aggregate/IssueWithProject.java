package ru.taska.domain.aggregate;

import ru.taska.domain.Issue;
import ru.taska.domain.dto.ProjectInfo;

/**
 * Задача вместе с информацией о проекте, к которому она относится.
 *
 * <p>Информация о проекте может отсутствовать, если проект не найден
 * или у задачи не указан идентификатор проекта.</p>
 */
public record IssueWithProject(
        Issue issue,
        ProjectInfo project
) {
    public static IssueWithProject of(Issue issue, ProjectInfo project) {
        return new IssueWithProject(issue, project);
    }
}
