package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.TaskaEvent;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OutboxEventMapper {

    private static final String SCHEMA_VERSION = "v1";

    @Value("${spring.application.name}")
    private String producerService;

    private final ObjectMapper objectMapper;

    public TaskaEvent toTaskaEvent(OutboxEvent event) {
        return TaskaEvent.builder()
                .id(event.getId())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType())
                .payload(event.getPayload())
                .requestId(event.getRequestId())
                .occurredAt(event.getCreatedAt())
                .producerService(producerService)
                .schemaVersion(SCHEMA_VERSION)
                .build();
    }

    public String toTaskaEventJsonAsString(OutboxEvent event) {
        return objectMapper.writeValueAsString(toTaskaEvent(event));
    }
}