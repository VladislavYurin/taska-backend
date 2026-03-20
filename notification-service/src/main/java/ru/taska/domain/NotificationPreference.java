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
 * Настройки уведомлений пользователя.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("notification_preferences")
public class NotificationPreference {

    /**
     * Идентификатор пользователя (один к одному с записью).
     */
    @Id
    @Column("user_id")
    private UUID userId;

    /**
     * Разрешены ли in‑app уведомления.
     */
    @Column("in_app_enabled")
    private boolean inAppEnabled;

    /**
     * Разрешены ли email‑уведомления.
     */
    @Column("email_enabled")
    private boolean emailEnabled;

    /**
     * Время последнего изменения настроек.
     */
    @Column("updated_at")
    private Instant updatedAt;
}