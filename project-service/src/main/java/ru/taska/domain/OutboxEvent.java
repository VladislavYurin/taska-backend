package ru.taska.domain;

import ru.taska.event.OutboxEventStatus;
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

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "outbox_events", schema = "taska")
public class OutboxEvent {

    @Id
    @Column("id")
    private UUID id;

    @Column("aggregate_type")
    private String aggregateType;

    @Column("aggregate_id")
    private UUID aggregateId;

    @Column("event_type")
    private String eventType;

    @Column("payload")
    private JsonNode payload;

    @Column("attempts")
    private Integer attempts;

    @Column("status")
    private OutboxEventStatus status;

    @Column("last_error_message")
    private String lastErrorMessage;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("published_at")
    private Instant publishedAt;

    @Column("processing_started_at")
    private Instant processingStartedAt;
}
