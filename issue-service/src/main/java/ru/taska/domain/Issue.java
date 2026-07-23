package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Основная сущность задачи.
 *
 * <p>Представляет единицу работы внутри проекта: задачу, ошибку или пользовательскую историю.
 * Каждая задача однозначно идентифицируется денормализованным ключом (например, ABC-123),
 * сформированным из префикса проекта и порядкового номера.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "issues", schema = "taska")
public class Issue {

    /**
     * Идентификатор задачи.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор проекта, к которому относится задача.
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Порядковый номер задачи внутри проекта (используется для формирования ключа, например ABC-123).
     */
    @Column("issue_number")
    private Integer issueNumber;

    /**
     * Денормализованный ключ задачи для быстрого поиска (например, ABC-123).
     */
    @Column("issue_key")
    private String issueKey;

    /**
     * Тип задачи (TASK / BUG / STORY).
     */
    @Column("issue_type")
    private IssueType issueType;

    /**
     * Краткое описание задачи.
     */
    @Column("summary")
    private String summary;

    /**
     * Подробное описание задачи.
     */
    @Column("description")
    private String description;

    /**
     * Текущий статус задачи (TODO / IN_PROGRESS / DONE).
     */
    @Column("status_key")
    private String statusKey;

    /**
     * Приоритет задачи (LOW / MEDIUM / HIGH).
     */
    @Column("priority")
    private IssuePriority priority;

    /**
     * Идентификатор исполнителя задачи.
     */
    @Column("assignee_id")
    private UUID assigneeId;

    /**
     * Идентификатор создателя задачи.
     */
    @Column("reporter_id")
    private UUID reporterId;

    /**
     * Версия задачи. Инкрементируется при каждом изменении содержимого задачи.
     */
    @Column("version")
    private Integer version;

    /**
     * Количество сторипоинтов выделенных на задачу
     */
    @Column("story_points")
    private Double storyPoints;

    /**
     * Время начала выполнения задачи
     */
    @Column("start_date")
    private Instant startDate;

    /**
     * Время окончания выполнения задачи
     */
    @Column("due_date")
    private Instant dueDate;

    /**
     * Изначальное количество времени выделенное на выполнение задачи
     */
    @Column("original_estimate_minutes")
    private Long originalEstimateMinutes;

    /**
     * Оставшееся количество времени на выполнение задачи
     */
    @Column("remaining_estimate_minutes")
    private Long remainingEstimateMinutes;

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

    /**
     * Временная метка мягкого удаления (null — запись активна).
     */
    @Column("deleted_at")
    private Instant deletedAt;
}