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
    private final EmailSenderService emailSenderService;
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
            log.warn("Received null or invalid event: {}", event);
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
                                .flatMap(saved -> createNotifications(event, eventId))
                                .as(transactionalOperator::transactional)
                )
                .onErrorResume(this::isDuplicateEvent, ex -> {
                    log.warn("Event was already processed: eventId={}", eventId);

                    return Mono.empty();
                })
                .flatMap(notifications ->
                        sendEmail(notifications, eventId)
                                .onErrorResume(ex -> {
                                    log.error("Failed to send email for event: eventId={}, message={}",
                                            eventId, ex.getMessage());

                                    return Mono.empty();
                                })
                );
    }

    private Mono<List<Notification>> createNotifications(TaskaEvent event, UUID eventId) {
        List<Notification> notifications = notificationFactory.create(event, eventId);

        if (notifications.isEmpty()) {
            log.debug("There aren't any notifications for event: eventId={}", eventId);

            return Mono.just(Collections.emptyList());
        }

        return Flux.fromIterable(notifications)
                .concatMap(notificationRepository::save)
                .collectList()
                .doOnNext(savedNotifications ->
                        log.info("Created {} notifications for event: eventId={}",
                                savedNotifications.size(), eventId)
                );
    }

    private Mono<Void> sendEmail(List<Notification> notifications, UUID eventId) {
        if (notifications == null || notifications.isEmpty()) {
            log.debug("There aren't any notifications to send for event: eventId={}", eventId);

            return Mono.empty();
        }

        return Flux.fromIterable(notifications)
                .flatMap(emailSenderService::sendIfEnabled)
                .then();
    }

    private boolean isDuplicateEvent(Throwable ex) {
        return ex instanceof DuplicateKeyException;
    }
}
