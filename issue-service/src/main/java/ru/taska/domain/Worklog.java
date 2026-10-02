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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Запись о фактически потраченном времени на выполнение задачи.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "issue_worklogs", schema = "taska")
public class Worklog {

    /**
     * Идентификатор лога.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор задачи, к которому относится worklog.
     */
    @Column("issue_id")
    private UUID issueId;

    /**
     * Идентификатор проекта, к которому относится worklog.
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Идентификатор автора.
     */
    @Column("author_user_id")
    private UUID authorUserId;

    /**
     * Время потраченное на задачу.
     */
    @Column("spent_minutes")
    private Integer spentMinutes;

    /**
     * Дата работы над задачей.
     */
    @Column("work_date")
    private LocalDate workDate;

    /**
     * Комментарий к worklog. Опционально.
     */
    @Column("comment")
    private String comment;

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

    /**
     * Версия задачи.
     */
    @Column("version")
    private Integer version;

    /**
     * Обновляет поля ворклога и актуализирует версию и время модификации.
     */
    public void update(Integer newSpentMinutes, LocalDate newWorkDate, String newComment) {
        if (newSpentMinutes != null) {
            this.spentMinutes = newSpentMinutes;
        }
        if (newWorkDate != null) {
            this.workDate = newWorkDate;
        }
        if (newComment != null) {
            this.comment = newComment;
        }

        touch();
    }

    /**
     * Обновляет системные поля аудита и оптимистической блокировки.
     */
    private void touch() {
        this.updatedAt = Instant.now();
        this.version = (this.version != null ? this.version : 0) + 1;
    }
}
