package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.event.TaskaEvent;
import com.google.protobuf.Timestamp;


import java.time.Instant;
import java.util.UUID;

@Component
public class NotificationMapper {

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
            case MEMBER_REMOVED -> NotificationKind.NOTIFICATION_KIND_MEMBER_REMOVED;
            case USER_ACTIVATED -> NotificationKind.NOTIFICATION_KIND_USER_ACTIVATED;
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
