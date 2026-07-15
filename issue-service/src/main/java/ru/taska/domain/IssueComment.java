package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 *
 * <p>Представляет собой текстовое сообщение, оставленное пользователем к задаче.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "issue_comments", schema = "taska")
public class IssueComment {

    /**
     * Идентификатор комментария.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор задачи, к которой относится комментарий.
     */
    @Column("issue_id")
    private UUID issueId;

    /**
     * Идентификатор проекта, к которому относится задача.
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Идентификатор пользователя, оставившего комментарий.
     */
    @Column("author_user_id")
    private UUID authorUserId;

    /**
     * Текст комментария.
     */
    @Column("body")
    private String body;

    /**
     * Временная метка создания комментария.
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка последнего изменения комментария.
     */
    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    /**
     * Временная метка мягкого удаления (null — комментарий активен).
     */
    @Column("deleted_at")
    private Instant deletedAt;

    /**
     * Версия комментария. Инкрементируется при каждом изменении.
     */
    @Column("version")
    private Integer version;
}