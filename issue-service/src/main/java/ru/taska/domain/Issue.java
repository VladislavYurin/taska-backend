package ru.taska.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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
     * Версия задачи. Инкрементируется при каждом изменении содержимого задачи.
     */
    @Column("version")
    private Integer version;

    /**
     * Временная метка мягкого удаления (null — запись активна).
     */
    @Column("deleted_at")
    private Instant deletedAt;

    /**
     * Story points задачи (абстрактная оценка сложности).
     */
    @Column("story_points")
    private BigDecimal storyPoints;

    /**
     * Дата начала работы над задачей.
     */
    @Column("start_date")
    private LocalDate startDate;

    /**
     * Дата окончания работы над задачей (дедлайн).
     */
    @Column("due_date")
    private LocalDate dueDate;

    /**
     * Исходная оценка времени в минутах.
     */
    @Column("original_estimate_minutes")
    private Integer originalEstimateMinutes;

    /**
     * Оставшееся время в минутах.
     */
    @Column("remaining_estimate_minutes")
    private Integer remainingEstimateMinutes;
}
