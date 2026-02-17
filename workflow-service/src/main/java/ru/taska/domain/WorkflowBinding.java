package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

/**
 * Привязка workflow к проекту и типу задачи.
 * <p>
 * Определяет, какой workflow используется
 * для конкретной комбинации (projectId, issueType).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowBinding {

    /**
     * Ссылка на projects, к которому привязан workflow.
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Тип задачи (например: TASK / BUG / STORY).
     */
    @Column("issue_type")
    private String issueType;

    /**
     * Идентификатор связанного workflow.
     */
    @Column("workflow_id")
    private UUID workflowId;

    /**
     * Временная метка создания записи (аудит).
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка последнего изменения записи (аудит).
     */
    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
