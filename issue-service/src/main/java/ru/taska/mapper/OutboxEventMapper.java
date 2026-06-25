package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.event.payload.issueService.IssueAssignedPayload;
import ru.taska.event.payload.issueService.IssueCreatedPayload;
import ru.taska.event.payload.issueService.IssueDeletedPayload;
import ru.taska.event.payload.issueService.IssueTransitionedPayload;
import ru.taska.event.payload.issueService.IssueUpdatedPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventMapper {

    private static final String REPORTER = "reporterId";
    private static final String ASSIGNEE = "assigneeId";
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
            case ISSUE_CREATED -> objectMapper.valueToTree(new IssueCreatedPayload(
                    getUuid(sourcePayload, REPORTER),
                    getUuid(sourcePayload, ASSIGNEE)
            ));

            case ISSUE_ASSIGNED -> objectMapper.valueToTree(new IssueAssignedPayload(
                    getUuid(sourcePayload, ASSIGNEE)
            ));

            case ISSUE_TRANSITIONED -> objectMapper.valueToTree(new IssueTransitionedPayload(
                    getUuid(sourcePayload, REPORTER),
                    getUuid(sourcePayload, ASSIGNEE)
            ));

            case ISSUE_DELETED -> objectMapper.valueToTree(new IssueDeletedPayload(
                    getUuid(sourcePayload, REPORTER),
                    getUuid(sourcePayload, ASSIGNEE)
            ));

            case ISSUE_UPDATED -> objectMapper.valueToTree(new IssueUpdatedPayload(
                    getUuid(sourcePayload, REPORTER),
                    getUuid(sourcePayload, ASSIGNEE)
            ));

            default -> sourcePayload;
        };
    }

    private UUID getUuid(JsonNode node, String field) {
        if (node == null) {
            return null;
        }

        var value = node.path(field).asString();

        if (value.isEmpty()) {
            return null;
        }

        return UUID.fromString(value);
    }
}
