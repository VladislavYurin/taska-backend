package ru.taska.mapper;


import ru.taska.dto.UserBlockedPayload;
import ru.taska.dto.UserUnblockedPayload;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.event.OutboxEventStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.entity.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.event.payload.UserActivatedPayload;
import ru.taska.event.payload.UserInvitedPayload;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventMapper {

    private static final String USER_ID = "userId";
    private static final String EMAIL = "email";
    private static final String INVITED_BY = "invitedBy";
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
        try {
            return objectMapper.writeValueAsString(toTaskaEvent(event));
        } catch (Exception e) {
            log.warn("Failed to serialize TaskaEvent for event id: {}", event.getId(), e);
            throw new RuntimeException("Failed to serialize TaskaEvent", e);
        }
    }

    public OutboxEvent buildUserBlockedOutboxEvent(User user, UserStatus oldStatus, String reason, UUID actorId, UUID requestId) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("userId", user.getId().toString())
                .put("oldStatus", oldStatus.name())
                .put("newStatus", user.getStatus().name())
                .put("reason", reason)
                .put("actorUserId", actorId.toString());

        return OutboxEvent.builder()
                .aggregateType("USER")
                .aggregateId(user.getId())
                .eventType(EventType.USER_BLOCKED.getValue())
                .status(OutboxEventStatus.NEW)
                .payload(payload)
                .attempts(0)
                .requestId(requestId.toString())
                .build();
    }

    public OutboxEvent buildUserUnblockedOutboxEvent(User user, UserStatus oldStatus, String reason, UUID actorId, UUID requestId) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("userId", user.getId().toString())
                .put("oldStatus", oldStatus.name())
                .put("newStatus", user.getStatus().name())
                .put("reason", reason)
                .put("actorUserId", actorId.toString());

        return OutboxEvent.builder()
                .aggregateType("USER")
                .aggregateId(user.getId())
                .eventType(EventType.USER_UNBLOCKED.getValue())
                .status(OutboxEventStatus.NEW)
                .payload(payload)
                .attempts(0)
                .requestId(requestId.toString())
                .build();
    }

    private JsonNode buildPayload(OutboxEvent event) {
        JsonNode sourcePayload = event.getPayload();
        EventType eventType = EventType.fromValue(event.getEventType());

        return switch (eventType) {
            case USER_INVITED -> objectMapper.valueToTree(new UserInvitedPayload(
                    getUuid(sourcePayload, USER_ID),
                    getString(sourcePayload, EMAIL),
                    getString(sourcePayload, INVITED_BY)
            ));

            case USER_ACTIVATED -> objectMapper.valueToTree(new UserActivatedPayload(
                    getUuid(sourcePayload, USER_ID),
                    getString(sourcePayload, EMAIL)
            ));

            case USER_BLOCKED -> objectMapper.valueToTree(new UserBlockedPayload(
                    getUuid(sourcePayload, "userId"),
                    getString(sourcePayload, "oldStatus"),
                    getString(sourcePayload, "newStatus"),
                    getString(sourcePayload, "reason"),
                    getUuid(sourcePayload, "actorUserId")
            ));

            case USER_UNBLOCKED -> objectMapper.valueToTree(new UserUnblockedPayload(
                    getUuid(sourcePayload, "userId"),
                    getString(sourcePayload, "oldStatus"),
                    getString(sourcePayload, "newStatus"),
                    getString(sourcePayload, "reason"),
                    getUuid(sourcePayload, "actorUserId")
            ));

            default -> {
                log.warn("Unknown event type: {}, using original payload", eventType);
                yield sourcePayload;
            }
        };
    }

    private UUID getUuid(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }

        try {
            return UUID.fromString(node.get(field).asText());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID in payload field={}", field, e);
            return null;
        }
    }

    private String getString(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }

        String value = node.get(field).asText();
        return value.isEmpty() ? null : value;
    }
}