package ru.taska.mapper;


import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.event.payload.authService.UserStatusChangedPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.entity.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.event.payload.authService.UserActivatedPayload;
import ru.taska.event.payload.authService.UserInvitedPayload;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventMapper {

    private static final String USER_ID = "userId";
    private static final String EMAIL = "email";
    private static final String INVITED_BY = "invitedBy";
    private static final String SCHEMA_VERSION = "v1";
    private static final String OLD_STATUS = "oldStatus";
    private static final String NEW_STATUS = "newStatus";
    private static final String ACTOR_ID = "actorUserId";
    private static final String REASON = "reason";

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

            case USER_BLOCKED, USER_UNBLOCKED -> objectMapper.valueToTree(new UserStatusChangedPayload(
                    getUuid(sourcePayload, USER_ID),
                    getString(sourcePayload, OLD_STATUS),
                    getString(sourcePayload, NEW_STATUS),
                    getString(sourcePayload, REASON),
                    getUuid(sourcePayload, ACTOR_ID)
            ));

            default -> {
                log.warn("Unknown event type: {}, using original payload", eventType);
                yield sourcePayload;
            }
        };
    }

    /**
     * Создает payload для USER_BLOCKED события
     * @param user пользователь с измененным статусом
     * @param oldStatus старый статус пользователя
     */
    public JsonNode buildUserBlockedPayload(
            User user,
            UserStatus oldStatus,
            UserStatusRequestDto requestDto
    ) {
        UserStatusChangedPayload payload = UserStatusChangedPayload.builder()
                .userId(user.getId())
                .oldStatus(oldStatus.name())
                .newStatus(user.getStatus().name())
                .reason(requestDto.reason())
                .actorUserId(requestDto.actorUserId())
                .build();

        return objectMapper.valueToTree(payload);
    }

    /**
     * Создает payload для USER_UNBLOCKED события
     * @param user пользователь с измененным статусом
     * @param oldStatus старый статус пользователя
     */
    public JsonNode buildUserUnblockedPayload(
            User user,
            UserStatus oldStatus,
            UserStatusRequestDto requestDto
    ) {
        UserStatusChangedPayload payload = UserStatusChangedPayload.builder()
                .userId(user.getId())
                .oldStatus(oldStatus.name())
                .newStatus(user.getStatus().name())
                .reason(requestDto.reason())
                .actorUserId(requestDto.actorUserId())
                .build();

        return objectMapper.valueToTree(payload);
    }

    ///====================== Utils ==========================

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