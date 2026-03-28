package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.domain.Notification;
import ru.taska.event.TaskaEvent;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFactory {

    private static final String ISSUE_ASSIGNED = "IssueAssigned";
    private static final String ISSUE_TRANSITIONED = "IssueTransitioned";
    private static final String USER_INVITED = "UserInvited";

    private final NotificationMapper notificationMapper;

    public List<Notification> create(TaskaEvent event, String eventId) {
        JsonNode payload = event.payload();
        return switch (event.eventType()) {
            case ISSUE_ASSIGNED     -> buildIssueAssigned(event, payload, eventId);
            case ISSUE_TRANSITIONED -> buildIssueTransitioned(event, payload, eventId);
            case USER_INVITED       -> buildUserInvited(event, eventId);
            default -> {
                log.info("Skip unsupported eventType={} eventId={}", event.eventType(), eventId);
                yield List.of();
            }
        };
    }

    private List<Notification> buildIssueAssigned(TaskaEvent event, JsonNode payload,String eventId) {
        UUID assigneeId = extractUuid(payload, "assigneeId");
        if (assigneeId == null) {
            log.warn("IssueAssigned event without assigneeId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toIssueAssigned(event, assigneeId));
    }

    private List<Notification> buildIssueTransitioned(TaskaEvent event, JsonNode payload,String eventId) {
        UUID reporterId = extractUuid(payload, "reporterId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (reporterId == null && assigneeId == null) {
            log.warn("IssueTransitioned event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        List<Notification> notifications = new ArrayList<>();

        if (reporterId != null) {
            notifications.add(notificationMapper.toIssueTransitioned(event, reporterId));
        }
        if (assigneeId != null && !assigneeId.equals(reporterId)) {
            notifications.add(notificationMapper.toIssueTransitioned(event, assigneeId));
        }

        return notifications;
    }

    private List<Notification> buildUserInvited(TaskaEvent event, String eventId) {
        UUID userId = event.aggregateId();
        if (userId == null) {
            log.warn("UserInvited event without aggregateId (user id), eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toUserInvited(event));
    }

    private UUID extractUuid(JsonNode payload, String fieldName) {
        if (payload == null || !payload.hasNonNull(fieldName)) {
            return null;
        }
        try {
            return UUID.fromString(payload.get(fieldName).asText());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID in payload field={} value={}", fieldName, payload.get(fieldName), e);
            return null;
        }
    }
}
