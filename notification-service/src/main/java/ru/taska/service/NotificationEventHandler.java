package ru.taska.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;
import tools.jackson.databind.JsonNode;

/**
 * Бизнес-логика обработки доменных событий из Kafka и создания уведомлений.
 */
@Service
@RequiredArgsConstructor
public class NotificationEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationEventHandler.class);

    private static final String ISSUE_ASSIGNED = "IssueAssigned";
    private static final String ISSUE_TRANSITIONED = "IssueTransitioned";
    private static final String USER_INVITED = "UserInvited";

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * Обрабатывает событие с дедупликацией по {@code eventId}.
     *
     * <p>Если событие уже было обработано ранее, метод завершится без
     * побочных эффектов.</p>
     */
    public Mono<Void> handle(TaskaEvent event) {
        if (event == null || event.id() == null) {
            LOGGER.warn("Received null or invalid event: {}", event);
            return Mono.empty();
        }

        String eventId = event.id().toString();

        return processedEventRepository.existsById(eventId)
                .flatMap(exists -> exists ? Mono.<Void>empty()
                        : markEventProcessedAndCreateNotifications(event, eventId));
    }

    private Mono<Void> markEventProcessedAndCreateNotifications(TaskaEvent event, String eventId) {
        ProcessedEvent processedEvent = ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now())
                .sourceType(event.aggregateType())
                .build();

        return createNotifications(event, eventId)
                .then(processedEventRepository.save(processedEvent))
                .then();
    }

    private Mono<Void> createNotifications(TaskaEvent event, String eventId) {
        List<Notification> notifications = switch (event.eventType()) {
            case ISSUE_ASSIGNED     -> buildIssueAssignedNotifications(event, eventId);
            case ISSUE_TRANSITIONED -> buildIssueTransitionedNotifications(event, eventId);
            case USER_INVITED       -> buildUserInvitedNotifications(event, eventId);
            default -> {
                LOGGER.info("Skip unsupported eventType={} eventId={}", event.eventType(), eventId);
                yield List.of();
            }
        };

        if (notifications.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(notifications)
                .flatMap(notificationRepository::save)
                .then();
    }

    private List<Notification> buildIssueAssignedNotifications(TaskaEvent event, String eventId) {
        JsonNode payload = event.payload();
        UUID assigneeId = extractUuid(payload, "assigneeId");
        if (assigneeId == null) {
            LOGGER.warn("IssueAssigned event without assigneeId, eventId={}", eventId);
            return List.of();
        }

        Notification notification = Notification.builder()
                .id(UUID.randomUUID())
                .userId(assigneeId)
                .notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Новая задача назначена на вас")
                .body("Вам назначена задача " + event.aggregateId())
                .createdAt(Instant.now())
                .sourceEventId(UUID.fromString(eventId))
                .build();

        return List.of(notification);
    }

    private List<Notification> buildIssueTransitionedNotifications(TaskaEvent event, String eventId) {
        JsonNode payload = event.payload();
        UUID reporterId = extractUuid(payload, "reporterId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (reporterId == null && assigneeId == null) {
            LOGGER.warn("IssueTransitioned event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        List<Notification> notifications = new ArrayList<>();

        if (reporterId != null) {
            notifications.add(buildIssueTransitionedNotificationForUser(
                    reporterId,
                    event,
                    eventId
            ));
        }
        if (assigneeId != null && !assigneeId.equals(reporterId)) {
            notifications.add(buildIssueTransitionedNotificationForUser(
                    assigneeId,
                    event,
                    eventId
            ));
        }

        return notifications;
    }

    private Notification buildIssueTransitionedNotificationForUser(
            UUID userId,
            TaskaEvent event,
            String eventId
    ) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .notificationType(NotificationType.ISSUE_TRANSITIONED)
                .title("Статус задачи изменён")
                .body("Статус задачи " + event.aggregateId() + " был изменён")
                .createdAt(Instant.now())
                .sourceEventId(UUID.fromString(eventId))
                .build();
    }

    private List<Notification> buildUserInvitedNotifications(TaskaEvent event, String eventId) {
        UUID userId = event.aggregateId();
        if (userId == null) {
            LOGGER.warn("UserInvited event without aggregateId (user id), eventId={}", eventId);
            return List.of();
        }

        Notification notification = Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .notificationType(NotificationType.USER_INVITED)
                .title("Добро пожаловать в Taska")
                .body("Завершите регистрацию, перейдя по ссылке из письма для задания пароля.")
                .createdAt(Instant.now())
                .sourceEventId(UUID.fromString(eventId))
                .build();

        return List.of(notification);
    }

    private UUID extractUuid(JsonNode payload, String fieldName) {
        if (payload == null || !payload.hasNonNull(fieldName)) {
            return null;
        }
        try {
            return UUID.fromString(payload.get(fieldName).asText());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid UUID in payload field={} value={}", fieldName, payload.get(fieldName), e);
            return null;
        }
    }
}

