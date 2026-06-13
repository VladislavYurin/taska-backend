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
 * Обработанное событие из Kafka.
 *
 * <p>Используется для дедупликации, чтобы одно и то же событие
 * не порождало дублирующие уведомления.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "processed_events", schema = "taska")
public class ProcessedEvent {

    /**
     * Идентификатор обработанного события.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Уникальный идентификатор события (eventId).
     */
    @Column("event_id")
    private String eventId;

    /**
     * Время, когда событие было успешно обработано.
     */
    @Column("processed_at")
    private Instant processedAt;

    /**
     * Тип источника события (ISSUE / PROJECT / USER).
     */
    @Column("source_type")
    private String sourceType;
}