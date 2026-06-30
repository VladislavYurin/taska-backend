package ru.taska.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Направленное ребро между двумя статусами в рамках workflow.
 * <p>
 * Определяет допустимое изменение состояния и соответствует действию пользователя
 * (кнопке) в пользовательском интерфейсе.
 * Перед применением перехода issue-service выполняет валидацию через workflow-service.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(schema = "taska", name = "transitions")
public class TransitionEntity {

    /**
     * Первичный ключ.
     */
    @Id
    private UUID id;

    /**
     * Ссылка на workflow, к которому относится данный переход.
     */
    @Column("workflow_id")
    private UUID workflowId;

    /**
     * Идентификатор исходного статуса (текущее состояние задачи).
     */
    @Column("from_status_id")
    private UUID fromStatusId;

    /**
     * Идентификатор целевого статуса (состояние задачи после перехода).
     */
    @Column("to_status_id")
    private UUID toStatusId;

    /**
     * Отображаемое имя действия в UI.
     */
    @Column("name")
    private String name;

    /**
     * Скрытие transition в UI.
     */
    @Column("is_hidden")
    private boolean hidden;

    /**
     * Определяет порядок отображения переходов,
     * если из одного статуса доступно несколько вариантов.
     */
    @Column("sort_order")
    private int sortOrder;

    /**
     * Временная метка создания записи (аудит).
     */
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка последнего изменения записи (аудит).
     */
    @Column("updated_at")
    private Instant updatedAt;
}
