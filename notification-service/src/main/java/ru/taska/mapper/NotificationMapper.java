package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAllAsReadResponse;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.config.props.NotificationProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationListResult;
import ru.taska.domain.NotificationType;
import ru.taska.event.IssueInfo;
import ru.taska.event.TaskaEvent;
import com.google.protobuf.Timestamp;


import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationMapper {

    private final NotificationProperties notificationProperties;

    public Notification toIssueCreated(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_CREATED)
                .title("New issue")
                .body(displayKey(issueInfo) + " was created")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueAssigned(TaskaEvent event, UUID assigneeId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(assigneeId)
                .notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Issue assigned to you")
                .body(displayKey(issueInfo) + " was assigned to you")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueUnassigned(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Assignee removed")
                .body(displayKey(issueInfo) + " no longer has an assignee")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueTransitioned(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_TRANSITIONED)
                .title("Status changed")
                .body("The status of " + displayKey(issueInfo) + " changed")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toProjectArchived(TaskaEvent event, UUID userId, UUID projectId, String name) {
        return Notification.builder().userId(userId).notificationType(NotificationType.PROJECT_ARCHIVED)
                .title("Project archived").body("Project " + name + " was archived")
                .projectId(projectId).createdAt(Instant.now()).sourceEventId(event.id()).build();
    }

    public Notification toIssueUpdated(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_UPDATED)
                .title("Issue updated")
                .body(displayKey(issueInfo) + " was updated")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueDeleted(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_DELETED)
                .title("Issue deleted")
                .body(displayKey(issueInfo) + " was deleted")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueLinkCreated(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String targetIssueKey,
            String linkType,
            UUID linkId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_LINK_CREATED)
                .title("Link added")
                .body(displayKey(issueInfo) + ": \"" + linkType + "\" link added to " + displayIssueKey(targetIssueKey))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueLinkDeleted(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String targetIssueKey,
            String linkType,
            UUID linkId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_LINK_DELETED)
                .title("Link removed")
                .body(displayKey(issueInfo) + ": \"" + linkType + "\" link removed from " + displayIssueKey(targetIssueKey))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserInvited(TaskaEvent event) {
        return Notification.builder()
                .userId(event.aggregateId())
                .notificationType(NotificationType.USER_INVITED)
                .title("Welcome to Taska")
                .body("Finish signing up: set your password with the link in your invitation email.")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toProjectCreated(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.PROJECT_CREATED)
                .title("Project created")
                .body(displayProject(event) + " was created")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toMemberAdded(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.MEMBER_ADDED)
                .title("You were added to a project")
                .body("You were added to " + displayProject(event))
                .projectId(memberProjectId(event))
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toMemberUpdated(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.MEMBER_UPDATED)
                .title("Your project role changed")
                .body("Your role in " + displayProject(event) + " changed")
                .projectId(memberProjectId(event))
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toMemberRemoved(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.MEMBER_REMOVED)
                .title("You were removed from a project")
                .body("You were removed from " + displayProject(event))
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserActivated(TaskaEvent event) {
        return Notification.builder()
                .userId(event.aggregateId())
                .notificationType(NotificationType.USER_ACTIVATED)
                .title("Account activated")
                .body("Your account is active. You can sign in now.")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    /** Redacts legacy previews even if an old worker writes after the cleanup migration. */
    public static String safeBody(Notification notification) {
        if (notification.getNotificationType() != NotificationType.ISSUE_COMMENT_CREATED) {
            return notification.getBody();
        }
        String key = notification.getIssueKey();
        if (key == null || key.isBlank()) {
            key = null;
        }
        return key == null ? "New comment" : "New comment on " + key;
    }

    public NotificationResponse toNotificationProto(Notification notification) {
        NotificationResponse.Builder builder = NotificationResponse.newBuilder()
                .setId(toStringOrEmpty(notification.getId()))
                .setUserId(toStringOrEmpty(notification.getUserId()))
                .setNotificationType(toProtoNotificationKind(notification.getNotificationType()))
                .setTitle(toStringOrEmpty(notification.getTitle()))
                .setBody(toStringOrEmpty(safeBody(notification)))
                .setSourceEventId(toStringOrEmpty(notification.getSourceEventId()))
                .setIssueId(toStringOrEmpty(notification.getIssueId()))
                .setIssueKey(toStringOrEmpty(notification.getIssueKey()))
                .setProjectId(toStringOrEmpty(notification.getProjectId()));

        if (notification.getCreatedAt() != null) {
            builder.setCreatedAt(toTimestamp(notification.getCreatedAt()));
        }

        if (notification.getReadAt() != null) {
            builder.setReadAt(toTimestamp(notification.getReadAt()));
        }

        return builder.build();
    }

    public ListNotificationsResponse toListNotificationsResponse(NotificationListResult result) {
        List<NotificationResponse> protoNotifications = result.notifications().stream()
                .map(this::toNotificationProto)
                .toList();

        return ListNotificationsResponse.newBuilder()
                .addAllNotifications(protoNotifications)
                    .setUnreadCount(result.unreadCount())
                .build();
    }

    public Notification toLabelAdded(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String labelName
    ) {        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.LABEL_ADDED)
                .title("Label added")
                .body("Label \"" + labelName + "\" added to " + displayKey(issueInfo))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toLabelRemoved(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String labelName) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.LABEL_REMOVED)
                .title("Label removed")
                .body("Label \"" + labelName + "\" removed from " + displayKey(issueInfo))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserBlocked(
            TaskaEvent event,
            UUID userId,
            String reason
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.USER_BLOCKED)
                .title("Account blocked")
                .body("Your account was blocked. Reason: " + reason)
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserUnblocked(
            TaskaEvent event,
            UUID userId,
            String reason
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.USER_UNBLOCKED)
                .title("Account unblocked")
                .body("Your account was unblocked. Reason: " + reason)
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toCommentCreated(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_COMMENT_CREATED)
                .title("New comment")
                .body("New comment on " + displayKey(issueInfo))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toCommentUpdated(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_COMMENT_UPDATED)
                .title("Comment edited")
                .body("A comment on " + displayKey(issueInfo) + " was edited")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toCommentDeleted(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_COMMENT_DELETED)
                .title("Comment deleted")
                .body("A comment on " + displayKey(issueInfo) + " was deleted")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public MarkAllAsReadResponse toMarkAllAsReadResponse(Long count) {
        return MarkAllAsReadResponse
                .newBuilder()
                .setUpdatedCount(count)
                .build();
    }

    public Notification toAttachmentAdded(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String fileName
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_ATTACHMENT_ADDED)
                .title("Attachment added")
                .body("\"" + fileName + "\" attached to " + displayKey(issueInfo))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toAttachmentDeleted(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String fileName
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_ATTACHMENT_DELETED)
                .title("Attachment removed")
                .body("\"" + fileName + "\" removed from " + displayKey(issueInfo))
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    private NotificationKind toProtoNotificationKind(NotificationType domain) {
        if (domain == null) {
            return NotificationKind.NOTIFICATION_KIND_UNSPECIFIED;
        }

        return switch (domain) {
            case ISSUE_ASSIGNED -> NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED;
            case ISSUE_TRANSITIONED -> NotificationKind.NOTIFICATION_KIND_ISSUE_TRANSITIONED;
            case ISSUE_CREATED -> NotificationKind.NOTIFICATION_KIND_ISSUE_CREATED;
            case ISSUE_UPDATED -> NotificationKind.NOTIFICATION_KIND_ISSUE_UPDATED;
            case ISSUE_DELETED -> NotificationKind.NOTIFICATION_KIND_ISSUE_DELETED;
            case USER_INVITED -> NotificationKind.NOTIFICATION_KIND_USER_INVITED;
            case PROJECT_CREATED -> NotificationKind.NOTIFICATION_KIND_PROJECT_CREATED;
            case PROJECT_ARCHIVED -> NotificationKind.NOTIFICATION_KIND_PROJECT_ARCHIVED;
            case MEMBER_ADDED -> NotificationKind.NOTIFICATION_KIND_MEMBER_ADDED;
            case MEMBER_UPDATED -> NotificationKind.NOTIFICATION_KIND_MEMBER_UPDATED;
            case MEMBER_REMOVED -> NotificationKind.NOTIFICATION_KIND_MEMBER_REMOVED;
            case USER_ACTIVATED -> NotificationKind.NOTIFICATION_KIND_USER_ACTIVATED;
            case ISSUE_LINK_CREATED -> NotificationKind.NOTIFICATION_KIND_ISSUE_LINK_CREATED;
            case ISSUE_LINK_DELETED -> NotificationKind.NOTIFICATION_KIND_ISSUE_LINK_DELETED;
            case LABEL_ADDED -> NotificationKind.NOTIFICATION_KIND_LABEL_ADDED;
            case LABEL_REMOVED -> NotificationKind.NOTIFICATION_KIND_LABEL_REMOVED;
            case USER_BLOCKED-> NotificationKind.NOTIFICATION_KIND_USER_BLOCKED;
            case USER_UNBLOCKED -> NotificationKind.NOTIFICATION_KIND_USER_UNBLOCKED;
            case ISSUE_COMMENT_CREATED -> NotificationKind.NOTIFICATION_KIND_ISSUE_COMMENT_CREATED;
            case ISSUE_COMMENT_UPDATED -> NotificationKind.NOTIFICATION_KIND_ISSUE_COMMENT_UPDATED;
            case ISSUE_COMMENT_DELETED -> NotificationKind.NOTIFICATION_KIND_ISSUE_COMMENT_DELETED;
            case ISSUE_ATTACHMENT_ADDED -> NotificationKind.NOTIFICATION_KIND_ISSUE_ATTACHMENT_ADDED;
            case ISSUE_ATTACHMENT_DELETED -> NotificationKind.NOTIFICATION_KIND_ISSUE_ATTACHMENT_DELETED;
        };
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private String toStringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Возвращает читаемый ключ задачи для текста уведомления
     */
    private String displayIssueKey(String key) {
        return key == null || key.isBlank() ? "the issue" : key;
    }

    private UUID memberProjectId(TaskaEvent event) {
        var payload = event.payload();
        if (payload != null && payload.hasNonNull("projectId")) {
            try {
                return UUID.fromString(payload.get("projectId").asString());
            } catch (IllegalArgumentException ignored) {
                // Legacy events can still identify their project by aggregate.
            }
        }
        return "project".equalsIgnoreCase(event.aggregateType()) ? event.aggregateId() : null;
    }

    private String displayProject(TaskaEvent event) {
        var payload = event.payload();
        String key = payload != null && payload.hasNonNull("projectKey")
                ? payload.get("projectKey").asString() : null;
        return key == null || key.isBlank() ? "the project" : "project " + key;
    }

    private String displayKey(IssueInfo issueInfo) {
        return displayIssueKey(issueInfo.issueKey());
    }
}
