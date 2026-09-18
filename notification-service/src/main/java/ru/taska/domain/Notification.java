package ru.taska.domain;

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
 * Уведомление для пользователя.
 *
 * <p>Используется как для in‑app inbox, так и как источник
 * для email‑отправки.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notifications", schema = "taska")
public class Notification {

    /**
     * Идентификатор уведомления.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор пользователя, которому адресовано уведомление.
     */
    @Column("user_id")
    private UUID userId;

    /**
     * Тип события, породившего уведомление (ISSUE_ASSIGNED, USER_INVITED и т.п.).
     */
    @Column("notification_type")
    private NotificationType notificationType;

    /**
     * Заголовок уведомления для отображения в UI / письме.
     */
    @Column("title")
    private String title;

    /**
     * Текст уведомления.
     */
    @Column("body")
    private String body;

    /**
     * Идентификатор задачи, к которой относится уведомление.
     * Null для не-issue уведомлений (USER_INVITED, MEMBER_ADDED и т.д.).
     */
    @Column("issue_id")
    private UUID issueId;

    /**
     * Человекочитаемый ключ задачи (например, ABC-123).
     * Null для не-issue уведомлений.
     */
    @Column("issue_key")
    private String issueKey;

    /**
     * Идентификатор проекта задачи.
     * Null для не-issue уведомлений.
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Время создания уведомления.
     */
    @Column("created_at")
    private Instant createdAt;

    /**
     * Время, когда уведомление было помечено прочитанным.
     */
    @Column("read_at")
    private Instant readAt;

    /**
     * Идентификатор исходного события (eventId из Kafka).
     * Используется для трассировки и дедупликации уведомлений.
     */
    @Column("source_event_id")
    private UUID sourceEventId;
}