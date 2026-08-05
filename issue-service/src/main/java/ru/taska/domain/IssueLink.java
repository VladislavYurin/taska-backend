package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Связь между задачами.
 *
 * <p>Устанавливает направленную связь между двумя задачами внутри системы.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "issue_links", schema = "taska")
public class IssueLink {

    /**
     * Идентификатор связи.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор проекта
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Идентификатор исходной задачи (источник связи).
     */
    @Column("source_issue_id")
    private UUID sourceIssueId;

    /**
     * Идентификатор целевой задачи (цель связи).
     */
    @Column("target_issue_id")
    private UUID targetIssueId;

    /**
     * Тип связи между задачами (BLOCKS / RELATES / DUPLICATES).
     */
    @Column("link_type")
    private IssueLinkType linkType;

    /**
     * Идентификатор пользователя, создавшего связь.
     */
    @Column("created_by")
    private UUID createdBy;

    /**
     * Временная метка создания связи.
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка мягкого удаления (null — запись активна).
     */
    @Column("deleted_at")
    private Instant deletedAt;
}
