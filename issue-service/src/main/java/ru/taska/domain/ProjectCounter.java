package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Счётчик задач проекта.
 *
 * <p>Хранит следующий порядковый номер задачи для каждого проекта.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "project_counters", schema = "taska")
public class ProjectCounter {

    /**
     * Идентификатор проекта (первичный ключ).
     */
    @Id
    @Column("project_id")
    private UUID projectId;

    /**
     * Следующий порядковый номер задачи в проекте.
     */
    @Column("next_issue_number")
    private Integer nextIssueNumber;
}
