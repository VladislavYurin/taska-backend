package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.event.TaskaEvent;

import java.time.Instant;
import java.util.UUID;

@Component
public class NotificationMapper {

    public Notification toIssueAssigned(TaskaEvent event, UUID assigneeId) {
        return Notification.builder()
                .id(UUID.randomUUID())
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
                .id(UUID.randomUUID())
                .userId(userId)
                .notificationType(NotificationType.ISSUE_TRANSITIONED)
                .title("Статус задачи изменён")
                .body("Статус задачи " + event.aggregateId() + " был изменён")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }

    public Notification toUserInvited(TaskaEvent event) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(event.aggregateId())
                .notificationType(NotificationType.USER_INVITED)
                .title("Добро пожаловать в Taska")
                .body("Завершите регистрацию, перейдя по ссылке из письма для задания пароля.")
                .createdAt(Instant.now())
                .sourceEventId(event.id())
                .build();
    }
}
