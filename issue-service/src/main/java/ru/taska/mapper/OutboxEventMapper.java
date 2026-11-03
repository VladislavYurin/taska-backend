package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.domain.entity.OutboxEvent;
import ru.taska.event.EventScopeKind;
import ru.taska.event.TaskaEvent;
import ru.taska.event.TaskaEventContract;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OutboxEventMapper {

    private static final String PRODUCER_SERVICE = "issue-service";

    private final ObjectMapper objectMapper;

    public TaskaEvent toTaskaEvent(OutboxEvent event) {
        return TaskaEvent.builder()
                .id(event.getId())
                .scopeKind(EventScopeKind.TENANT)
                .tenantId(event.getTenantId())
                .scopeId(event.getTenantId())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType())
                .payload(event.getPayload())
                .requestId(event.getRequestId())
                .occurredAt(event.getCreatedAt())
                .producerService(PRODUCER_SERVICE)
                .schemaVersion(TaskaEventContract.SCHEMA_VERSION)
                .build();
    }

    public String toTaskaEventJsonAsString(OutboxEvent event) {
        return objectMapper.writeValueAsString(
                TaskaEventContract.requireValid(toTaskaEvent(event)));
    }
}
