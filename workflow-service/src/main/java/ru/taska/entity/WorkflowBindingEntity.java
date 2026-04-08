package ru.taska.entity;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Привязка workflow к проекту и типу задачи.
 * <p>
 * Определяет, какой workflow используется
 * для конкретной комбинации (projectId, issueType).
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.workflow_bindings")
public class WorkflowBindingEntity {

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
