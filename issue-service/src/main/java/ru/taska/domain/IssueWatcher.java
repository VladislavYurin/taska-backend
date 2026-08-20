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
 * Подписчик задачи.
 * <p>
 * Означает, что пользователь следит за задачей и должен получать уведомления
 * о событиях по ней.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table( name = "issue_watchers", schema = "taska")
public class IssueWatcher {

    @Id
    @Column("id")
    private UUID id;

    @Column("issue_id")
    private UUID issueId;

    @Column("project_id")
    private UUID projectId;

    @Column("user_id")
    private UUID userId;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private UUID createdBy;


}
