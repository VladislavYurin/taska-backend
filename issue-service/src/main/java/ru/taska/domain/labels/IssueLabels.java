package ru.taska.domain.labels;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Сущность, связывающая задачу и метки из projectLabels
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "issue_labels",schema = "taska")
public class IssueLabels {

    /**
     * Идентификатор связи задачи и метки
     */
    @Id
    private UUID id;

    /**
     * Идентификатор задачи
     */
    @Column("issue_id")
    private UUID issueId;

    /**
     * Идентификатор метки
     */
    @Column("label_id")
    private UUID labelId;

    /**
     * Идентификатор создателя задачи.
     */
    @Column("created_by")
    private UUID createdBy;

    /**
     * Временная метка создания записи
     */
    @Column("created_at")
    private Instant createdAt;
}