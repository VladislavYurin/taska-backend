package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.domain.Notification;
import ru.taska.event.EventType;
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

    private final NotificationMapper notificationMapper;

    public List<Notification> create(TaskaEvent event, UUID eventId) {
        JsonNode payload = event.payload();
        EventType type = EventType.fromValue(event.eventType());

        return switch (type) {
            case ISSUE_CREATED -> buildIssueCreated(event, payload, eventId);
            case ISSUE_ASSIGNED -> buildIssueAssigned(event, payload, eventId);
            case ISSUE_TRANSITIONED -> buildIssueTransitioned(event, payload, eventId);
            case ISSUE_UPDATED -> buildIssueUpdated(event, payload, eventId);
            case ISSUE_DELETED -> buildIssueDeleted(event, payload, eventId);
            case ISSUE_LINK_CREATED -> buildIssueLinkCreated(event, payload, eventId);
            case ISSUE_LINK_DELETED -> buildIssueLinkDeleted(event, payload, eventId);
            case USER_INVITED -> buildUserInvited(event, eventId);
            case PROJECT_CREATED -> buildProjectCreated(event, payload, eventId);
            case MEMBER_ADDED -> buildMemberAdded(event, payload, eventId);
            case MEMBER_UPDATED -> buildMemberUpdated(event, payload, eventId);
            case MEMBER_REMOVED -> buildMemberRemoved(event, payload, eventId);
            case USER_ACTIVATED -> buildUserActivated(event, eventId);
            case ISSUE_LABEL_ADDED -> buildLabelAdded(event, payload, eventId);
            case ISSUE_LABEL_REMOVED -> buildLabelRemoved(event, payload, eventId);
            default -> {
                log.info("Skip unsupported eventType={} eventId={}", event.eventType(), eventId);
                yield List.of();
            }
        };
    }

    private List<Notification> buildIssueCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID reporterId = extractUuid(payload, "reporterId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (reporterId == null && assigneeId == null) {
            log.warn("IssueCreated event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        List<Notification> notifications = new ArrayList<>();

        if (reporterId != null) {
            notifications.add(notificationMapper.toIssueCreated(event, reporterId));
        }

        if (assigneeId != null && !assigneeId.equals(reporterId)) {
            notifications.add(notificationMapper.toIssueCreated(event, assigneeId));
        }

        return notifications;
    }

    private List<Notification> buildIssueAssigned(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID assigneeId = extractUuid(payload, "assigneeId");
        if (assigneeId == null) {
            log.warn("IssueAssigned event without assigneeId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toIssueAssigned(event, assigneeId));
    }

    private List<Notification> buildIssueTransitioned(TaskaEvent event, JsonNode payload, UUID eventId) {
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

    private List<Notification> buildIssueUpdated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID reporterId = extractUuid(payload, "reporterId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (reporterId == null && assigneeId == null) {
            log.warn("IssueUpdated event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        List<Notification> notifications = new ArrayList<>();

        if (reporterId != null) {
            notifications.add(notificationMapper.toIssueUpdated(event, reporterId));
        }

        if (assigneeId != null && !assigneeId.equals(reporterId)) {
            notifications.add(notificationMapper.toIssueUpdated(event, assigneeId));
        }

        return notifications;
    }

    private List<Notification> buildIssueDeleted(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID reporterId = extractUuid(payload, "reporterId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (reporterId == null && assigneeId == null) {
            log.warn("IssueDeleted event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        List<Notification> notifications = new ArrayList<>();

        if (reporterId != null) {
            notifications.add(notificationMapper.toIssueDeleted(event, reporterId));
        }

        if (assigneeId != null && !assigneeId.equals(reporterId)) {
            notifications.add(notificationMapper.toIssueDeleted(event, assigneeId));
        }

        return notifications;
    }

    private List<Notification> buildIssueLinkDeleted(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "deletedBy");
        UUID sourceIssueId = extractUuid(payload, "sourceIssueId");
        UUID targetIssueId = extractUuid(payload, "targetIssueId");
        String linkType = extractString(payload, "linkType");
        UUID linkId = event.aggregateId();

        if (userId == null) {
            log.warn("IssueLinkDeleted event without reporterId, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toIssueLinkDeleted(event, userId, sourceIssueId, targetIssueId, linkType, linkId));
    }

    private List<Notification> buildUserInvited(TaskaEvent event, UUID eventId) {
        UUID userId = event.aggregateId();
        if (userId == null) {
            log.warn("UserInvited event without aggregateId (user id), eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toUserInvited(event));
    }

    private List<Notification> buildProjectCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID createdBy = extractUuid(payload, "createdBy");
        if (createdBy == null) {
            log.warn("ProjectCreated event without createdBy, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toProjectCreated(event, createdBy));
    }

    private List<Notification> buildMemberAdded(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        if (userId == null) {
            log.warn("MemberAdded event without userId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toMemberAdded(event, userId));
    }

    private List<Notification> buildMemberUpdated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        if (userId == null) {
            log.warn("MemberUpdated event without userId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toMemberUpdated(event, userId));
    }

    private List<Notification> buildMemberRemoved(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        if (userId == null) {
            log.warn("MemberRemoved event without userId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toMemberRemoved(event, userId));
    }

    private List<Notification> buildUserActivated(TaskaEvent event, UUID eventId) {
        UUID userId = event.aggregateId();
        if (userId == null) {
            log.warn("UserActivated event without aggregateId (user id), eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toUserActivated(event));
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

    private String extractString(JsonNode payload, String fieldName) {
        if (payload == null || !payload.hasNonNull(fieldName)) {
            return null;
        }
        try {
            return payload.get(fieldName).asText();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid String in payload field={} value={}", fieldName, payload.get(fieldName), e);
            return null;
        }
    }

    private List<Notification> buildLabelAdded(TaskaEvent event, JsonNode payload, UUID eventId) {

        UUID issueId = extractUuid(payload, "issueId");
        UUID addedBy = extractUuid(payload, "createdBy");
        String labelName = extractString(payload, "labelName");

        if (issueId == null || addedBy == null || labelName == null) {
            log.warn("LabelAdded event missing required fields, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toLabelAdded(event, issueId, addedBy, labelName));
    }

    private List<Notification> buildIssueLinkCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "createdBy");
        UUID sourceIssueId = extractUuid(payload, "sourceIssueId");
        UUID targetIssueId = extractUuid(payload, "targetIssueId");
        String linkType = extractString(payload, "linkType");
        UUID linkId = event.aggregateId();

        if (userId == null) {
            log.warn("IssueLinkCreated event without reporterId, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toIssueLinkCreated(event, userId, sourceIssueId, targetIssueId, linkType, linkId));
    }


    private List<Notification> buildLabelRemoved(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID issueId = extractUuid(payload, "issueId");
        UUID removedBy = extractUuid(payload, "deletedBy");
        String labelName = extractString(payload, "labelName");

        if (issueId == null || removedBy == null || labelName == null) {
            log.warn("LabelRemoved event missing required fields, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toLabelRemoved(event, issueId, removedBy, labelName));
    }

}
