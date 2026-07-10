package ru.taska.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Статус внутри workflow.
 * <p>
 * Представляет возможное состояние задачи (Issue) в рамках конкретного workflow.
 * Статусы являются вершинами графа workflow и используются issue-service
 * как источник истины допустимых значений состояния.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "statuses", schema = "taska" )
public class StatusEntity {

    /**
     * Первичный ключ.
     */
    @Id
    private UUID id;

    /**
     * Ссылка на workflow, к которому относится данный статус.
     */
    @Column("workflow_id")
    private UUID workflowId;

    /**
     * Стабильный ключ статуса, используемый другими сервисами и в снимках состояния (snapshot).
     * Пример: TODO / IN_PROGRESS / DONE.
     */
    @Column("status_key")
    private String statusKey;

    /**
     * Отображаемое имя статуса в пользовательском интерфейсе.
     */
    @Column("name")
    private String name;

    /**
     * Логическая категория статуса для группировки и семантической интерпретации.
     * <p>
     * Пример: TODO / IN_PROGRESS / DONE.
     */
    @Column("category")
    private String category;

    /**
     * Определяет порядок отображения статусов внутри workflow (например, порядок колонок на доске).
     */
    @Column("sort_order")
    private int sortOrder;

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
