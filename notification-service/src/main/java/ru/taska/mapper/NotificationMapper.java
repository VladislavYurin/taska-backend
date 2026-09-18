package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.config.props.NotificationProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.event.IssueInfo;
import ru.taska.event.TaskaEvent;
import com.google.protobuf.Timestamp;


import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationMapper {

    private final NotificationProperties notificationProperties;
    private final String ETC_SIGN = "...";

    public Notification toIssueCreated(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_CREATED)
                .title("Новая задача")
                .body("Создана новая задача " + displayKey(issueInfo))
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
                .title("Новая задача назначена на вас")
                .body("Вам назначена задача " + displayKey(issueInfo))
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
                .title("Статус задачи изменён")
                .body("Статус задачи " + displayKey(issueInfo) + " был изменён")
                .issueId(issueInfo.issueId())
                .issueKey(issueInfo.issueKey())
                .projectId(issueInfo.projectId())
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toIssueUpdated(TaskaEvent event, UUID userId, IssueInfo issueInfo) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_UPDATED)
                .title("Задача обновлена")
                .body("Задача обновлена " + displayKey(issueInfo))
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
                .title("Задача удалена")
                .body("Задача удалена " + displayKey(issueInfo))
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
            UUID targetIssueId,
            String linkType,
            UUID linkId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_LINK_CREATED)
                .title("Для задачи установлена новая связь")
                .body("Для задачи " + displayKey(issueInfo)
                        + " установлена связь \"" + linkType + "\" с задачей "
                        + targetIssueId + ".")
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
            UUID targetIssueId,
            String linkType,
            UUID linkId
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.ISSUE_LINK_DELETED)
                .title("Для задачи удалена связь")
                .body("Для задачи " + displayKey(issueInfo)
                        + " удалена связь \"" + linkType + "\" с задачей "
                        + targetIssueId + ".")
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

    public Notification toLabelAdded(
            TaskaEvent event,
            UUID userId,
            IssueInfo issueInfo,
            String labelName
    ) {
        return Notification.builder()
                .userId(userId)
                .notificationType(NotificationType.LABEL_ADDED)
                .title("Метка добавлена к задаче")
                .body("К задаче " + displayKey(issueInfo)
                        + " добавлена метка \"" + labelName + "\"")
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
                .title("Метка удалена из задачи")
                .body("Из задачи " + displayKey(issueInfo)
                        + " удалена метка \"" + labelName + "\"")
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
            IssueInfo issueInfo,
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
                .body("Новый комментарий к задаче " + displayKey(issueInfo)
                        + ": " + preview)
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
                .title("Комментарий обновлён")
                .body("Комментарий к задаче " + displayKey(issueInfo) + " был обновлён")
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
                .title("Комментарий удалён")
                .body("Комментарий к задаче " + displayKey(issueInfo) + " был удалён")
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

    /**
     * Возвращает читаемый ключ задачи для текста уведомления
     */
    private String displayKey(IssueInfo issueInfo) {
        if (issueInfo.issueKey() != null && !issueInfo.issueKey().isBlank()) {
            return issueInfo.issueKey();
        }
        return issueInfo.issueId().toString();
    }
}
