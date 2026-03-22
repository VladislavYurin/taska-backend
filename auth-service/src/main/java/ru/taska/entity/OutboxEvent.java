package ru.taska.entity;

import tools.jackson.databind.JsonNode;
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
 * Событие transactional outbox.
 *
 * <p>Используется для гарантированной публикации доменных событий
 * во внешний брокер сообщений (Kafka).</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "outbox_events", schema = "taska")
public class OutboxEvent {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Тип агрегата (например, USER).
     */
    @Column("aggregate_type")
    private String aggregateType;

    /**
     * Идентификатор агрегата, к которому относится событие.
     */
    @Column("aggregate_id")
    private UUID aggregateId;

    /**
     * Тип доменного события (UserInvited, UserActivated и т.п.).
     * */
    @Column("event_type")
    private String eventType;

    /**
     * Полезная нагрузка события в формате JSON.
     */
    @Column("payload")
    private JsonNode payload;

    /**
     * Количество попыток публикации события.
     */
    @Column("attempts")
    private Integer attempts;

    /**
     * Сообщение об ошибке последней попытки публикации.
     */
    @Column("last_error_message")
    private String lastErrorMessage;

    /**
     * Временная метка создания записи (аудит).
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Время успешной публикации события во внешний брокер.
     */
    @Column("published_at")
    private Instant publishedAt;
}
