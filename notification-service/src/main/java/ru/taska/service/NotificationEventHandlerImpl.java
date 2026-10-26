package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Бизнес-логика обработки доменных событий из Kafka и создания уведомлений.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventHandlerImpl implements NotificationEventHandler {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationFactory notificationFactory;
    private final NotificationDeliveryQueue deliveryQueue;
    private final NotificationSettingsService settings;
    private final InvitationEmailQueue invitationEmailQueue;
    private final TransactionalOperator transactionalOperator;

    /**
     * Обрабатывает событие с дедупликацией по {@code eventId}.
     *
     * <p>Если событие уже было обработано ранее, метод завершится без
     * побочных эффектов.</p>
     */
    @Override
    public Mono<Void> handle(TaskaEvent event) {
        if (event == null || event.id() == null) {
            log.warn("Received null event or event without an id");
            return Mono.empty();
        }

        UUID eventId = event.id();

        ProcessedEvent processedEvent = ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now())
                .sourceType(event.aggregateType())
                .build();

        return Mono.defer(() ->
                        processedEventRepository.save(processedEvent)
                                .flatMap(saved -> provisionUserEmail(event).then(createNotifications(event, eventId)))
                                .as(transactionalOperator::transactional)
                )
                .onErrorResume(this::isDuplicateEvent, ex -> {
                    log.warn("Event was already processed: eventId={}", eventId);

                    return Mono.empty();
                }).then();
    }

    private Mono<List<Notification>> createNotifications(TaskaEvent event, UUID eventId) {
        List<Notification> notifications = notificationFactory.create(event, eventId);

        if (notifications.isEmpty()) {
            log.debug("There aren't any notifications for event: eventId={}", eventId);

            return Mono.just(Collections.emptyList());
        }

        Mono<Void> invitation = ru.taska.event.EventType.USER_INVITED.getValue().equals(event.eventType())
                ? invitationEmailQueue.enqueue(event) : Mono.empty();
        return invitation.thenMany(Flux.fromIterable(notifications))
                .concatMap(n -> notificationRepository.save(n).flatMap(saved -> deliveryQueue.enqueue(saved).thenReturn(saved)))
                .collectList()
                .doOnNext(savedNotifications ->
                        log.info("Created {} notifications for event: eventId={}",
                                savedNotifications.size(), eventId)
                );
    }

    private Mono<Void> provisionUserEmail(TaskaEvent event) {
        var type = ru.taska.event.EventType.fromValue(event.eventType());
        if ((type == ru.taska.event.EventType.PERSONAL_ACCESS_TOKEN_CREATED
                || type == ru.taska.event.EventType.PERSONAL_ACCESS_TOKEN_REVOKED)
                && event.aggregateId() != null && event.payload() != null
                && event.payload().path("email").isString()) {
            return settings.ensureMissing(event.aggregateId(), event.payload().path("email").asText());
        }
        if ((type != ru.taska.event.EventType.USER_ACTIVATED && type != ru.taska.event.EventType.USER_EMAIL_CHANGED)
                || event.aggregateId() == null || event.payload() == null
                || !event.payload().path("email").isString()) return Mono.empty();
        return settings.ensure(event.aggregateId(), event.payload().path("email").asText())
                .then(settings.cancelObsolete(event.aggregateId()));
    }

    private boolean isDuplicateEvent(Throwable ex) {
        return ex instanceof DuplicateKeyException;
    }
}
