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
import ru.taska.event.TaskaEvent;
import com.google.protobuf.Timestamp;


import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationMapper {

    private final NotificationProperties notificationProperties;
    private final String ETC_SIGN = "...";

    public Notification toIssueCreated(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_CREATED)
                .title("Новая задача")
                .body("Создана новая задача " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueAssigned(TaskaEvent event, UUID assigneeId) {
        return Notification.builder()
                .userId(assigneeId)
                .notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Новая задача назначена на вас")
                .body("Вам назначена задача " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueTransitioned(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_TRANSITIONED)
                .title("Статус задачи изменён")
                .body("Статус задачи " + event.aggregateId() + " был изменён")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueUpdated(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_UPDATED)
                .title("Задача обновлена")
                .body("Задача обновлена " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueDeleted(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_DELETED)
                .title("Задача удалена")
                .body("Задача удалена " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueLinkCreated(
            TaskaEvent event,
            UUID userId,
            UUID sourceIssueId,
            UUID targetIssueId,
            String linkType,
            UUID linkId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_LINK_CREATED)
                .title("Для задачи установлена новая связь")
                .body("Установлена новая связь %s (%s) для задачи %s с задачей %s."
                        .formatted(linkId, linkType, sourceIssueId, targetIssueId))
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueLinkDeleted(
            TaskaEvent event,
            UUID userId,
            UUID sourceIssueId,
            UUID targetIssueId,
            String linkType,
            UUID linkId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_LINK_DELETED)
                .title("Связь удалена из задачи")
                .body("Была удалена связь %s (%s) между задачами %s и %s."
                        .formatted(linkId, linkType, sourceIssueId, targetIssueId))
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserInvited(TaskaEvent event) {
        return Notification.builder()
                .userId(event.aggregateId())
                .notificationType(NotificationType.USER_INVITED)
                .title("Добро пожаловать в Taska")
                .body("Завершите регистрацию, перейдя по ссылке из письма для задания пароля.")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toProjectCreated(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.PROJECT_CREATED)
                .title("Проект создан")
                .body("Создан новый проект " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toMemberAdded(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.MEMBER_ADDED)
                .title("Вас добавили в проект")
                .body("Вы добавлены в проект " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toMemberUpdated(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.MEMBER_UPDATED)
                .title("Ваша роль в проекте изменена")
                .body("Ваша роль изменена в проекте " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toMemberRemoved(TaskaEvent event, UUID userId) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.MEMBER_REMOVED)
                .title("Вас удалили из проекта")
                .body("Вы удалены из проекта " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserActivated(TaskaEvent event) {
        return Notification.builder()
                .userId(event.aggregateId())
                .notificationType(NotificationType.USER_ACTIVATED)
                .title("Аккаунт активирован")
                .body("Ваш аккаунт успешно активирован. Теперь вы можете войти в систему.")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public NotificationResponse toNotificationProto(Notification notification) {
        NotificationResponse.Builder builder = NotificationResponse.newBuilder()
                .setId(toStringOrEmpty(notification.getId()))
                .setUserId(toStringOrEmpty(notification.getUserId()))
                .setNotificationType(toProtoNotificationKind(notification.getNotificationType()))
                .setTitle(toStringOrEmpty(notification.getTitle()))
                .setBody(toStringOrEmpty(notification.getBody()))
                .setLink(toStringOrEmpty(notification.getLink()))
                .setSourceEventId(toStringOrEmpty(notification.getSourceEventId()));

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

    public Notification toLabelAdded(TaskaEvent event, UUID issueId, UUID userId, String labelName) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.LABEL_ADDED)
                .title("Метка добавлена к задаче")
                .body("К задаче " + issueId + " добавлена метка \"" + labelName + "\"")
                .link("/issues/" + issueId)
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toLabelRemoved(TaskaEvent event, UUID issueId, UUID userId, String labelName) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.LABEL_REMOVED)
                .title("Метка удалена из задачи")
                .body("Из задачи " + issueId + " удалена метка \"" + labelName + "\"")
                .link("/issues/" + issueId)
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
                .title("Account Blocked")
                .body(String.format("Your account has been blocked. Reason: %s", reason))
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
                .title("Account Unblocked")
                .body(String.format("Your account has been unblocked. Reason: %s", reason))
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toCommentCreated(
            TaskaEvent event,
            UUID userId,
            String body
    ) {
        int maxCommentBodyLength = notificationProperties.comment().maxShownBodyLength();

        String preview = body != null && body.length() > maxCommentBodyLength
                ? body.substring(0, maxCommentBodyLength - ETC_SIGN.length()) + ETC_SIGN
                : body;

        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_COMMENT_CREATED)
                .title("Новый комментарий")
                .body("Новый комментарий к задаче " + event.aggregateId() + ": " + preview)
                .link("/issues/" + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toCommentUpdated(
            TaskaEvent event,
            UUID userId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_COMMENT_UPDATED)
                .title("Комментарий обновлён")
                .body("Комментарий к задаче " + event.aggregateId() + " был обновлён")
                .link("/issues/" + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toCommentDeleted(
            TaskaEvent event,
            UUID userId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_COMMENT_DELETED)
                .title("Комментарий удалён")
                .body("Комментарий к задаче " + event.aggregateId() + " был удалён")
                .link("/issues/" + event.aggregateId())
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
}
