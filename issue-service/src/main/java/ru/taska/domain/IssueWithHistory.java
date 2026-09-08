package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.taska.domain.labels.ProjectLabels;

import java.util.Collections;
import java.util.List;

/**
 * Агрегат: задача вместе с историей её изменений.
 * Конструктор (issue, history), где метки в ответе не нужны. Например: executeTransition.
 */
@Data
@AllArgsConstructor
public class IssueWithHistory {

    private Issue issue;

    private List<IssueHistory> history;

    private List<ProjectLabels> labels;

    public IssueWithHistory(Issue issue, List<IssueHistory> history) {
        this.issue = issue;
        this.history = history;
        this.labels = Collections.emptyList();
    }
}
