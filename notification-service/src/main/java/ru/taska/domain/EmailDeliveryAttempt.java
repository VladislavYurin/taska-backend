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
 * Попытка доставки email‑уведомления.
 *
 * <p>Хранит историю попыток отправки письма по конкретному уведомлению,
 * включая статус, номер попытки и плановое время следующего повтора.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("email_delivery_attempts")
public class EmailDeliveryAttempt {

    /**
     * Идентификатор записи о попытке отправки.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор уведомления, для которого выполнялась отправка.
     */
    @Column("notification_id")
    private UUID notificationId;

    /**
     * Email-адрес получателя.
     */
    @Column("to_email")
    private String toEmail;

    /**
     * Тема письма
     */
    @Column("subject")
    private String subject;

    /**
     * Текущий статус попытки (PENDING / FAILED / SENT).
     */
    @Column("status")
    private EmailAttemptStatus status;

    /**
     * Количество совершенных попыток отправки
     */
    @Column("attempts")
    private int attempts;

    /**
     * Текст ошибки последней неудачной попытки, если есть.
     */
    @Column("last_error")
    private String lastError;

    /**
     * Плановое время следующей попытки отправки (для retry‑механизма).
     */
    @Column("next_retry_at")
    private Instant nextRetryAt;

    /**
     * Время создания записи о попытке.
     */
    @Column("created_at")
    private Instant createdAt;

    /**
     * Время последнего обновления записи
     */
    @Column("updated_at")
    private Instant updatedAt;
}