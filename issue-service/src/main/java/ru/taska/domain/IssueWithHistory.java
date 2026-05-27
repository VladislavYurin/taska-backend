package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Агрегат: задача вместе с историей её изменений.
 */
@Data
@AllArgsConstructor
public class IssueWithHistory {

    private Issue issue;

    private List<IssueHistory> history;
}
