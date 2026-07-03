package ru.taska.mapper;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.event.payload.projectService.MemberAddedPayload;
import ru.taska.event.payload.projectService.MemberRemovedPayload;
import ru.taska.event.payload.projectService.ProjectCreatedPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Преобразует {@link OutboxEvent} в публичное событие {@link TaskaEvent} для отправки в Kafka.
 *
 * <p>Из «сырого» payload (снимок доменной сущности) собирается узкий контракт под конкретный
 * тип события, чтобы наружу уходили только релевантные поля.</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxEventMapper {

    private static final String ID = "id";
    private static final String PROJECT_ID = "projectId";
    private static final String PROJECT_KEY = "projectKey";
    private static final String USER_ID = "userId";
    private static final String ROLE = "role";
    private static final String CREATED_BY = "createdBy";
    private static final String ADDED_BY = "addedBy";
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
                         .payload(buildPayload(event))
                         .requestId(event.getRequestId())
                         .occurredAt(event.getCreatedAt())
                         .producerService(producerService)
                         .schemaVersion(SCHEMA_VERSION)
                         .build();
    }

    public String toTaskaEventJsonAsString(OutboxEvent event) {
        return objectMapper.writeValueAsString(toTaskaEvent(event));
    }

    private JsonNode buildPayload(OutboxEvent event) {
        JsonNode sourcePayload = event.getPayload();
        EventType eventType = EventType.fromValue(event.getEventType());

        return switch (eventType) {
            case PROJECT_CREATED -> objectMapper.valueToTree(new ProjectCreatedPayload(
                    getUuid(sourcePayload, PROJECT_ID),
                    getString(sourcePayload, PROJECT_KEY),
                    getUuid(sourcePayload, CREATED_BY)
            ));

            case MEMBER_ADDED -> objectMapper.valueToTree(new MemberAddedPayload(
                    getUuid(sourcePayload, PROJECT_ID),
                    getUuid(sourcePayload, USER_ID),
                    getString(sourcePayload, ROLE),
                    getUuid(sourcePayload, ADDED_BY)
            ));

            case MEMBER_REMOVED -> objectMapper.valueToTree(new MemberRemovedPayload(
                    getUuid(sourcePayload, PROJECT_ID),
                    getUuid(sourcePayload, USER_ID)
            ));

            default -> sourcePayload;
        };
    }

    private UUID getUuid(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }

        return UUID.fromString(node.get(field).asString());
    }

    private String getString(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }

        return node.get(field).asString();
    }
}
