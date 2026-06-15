package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;

import java.time.Instant;
import java.util.List;

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

        return processedEventRepository.save(processedEvent)
                .then(createNotifications(event, eventId));
    }

    private Mono<Void> createNotifications(TaskaEvent event, String eventId) {
        List<Notification> notifications = notificationFactory.create(event, eventId);

        if (notifications.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(notifications)
                .flatMap(notificationRepository::save)
                .flatMap(savedNotification ->
                        emailSenderService.sendIfEnabled(savedNotification)
                                .thenReturn(savedNotification)
                )
                .doOnNext(notification ->
                        log.info("Notification successfully saved: id={}", notification.getId())
                )
                .doOnError(ex ->
                        log.error("Failed to save notification for event: eventId={}, message: {}",
                                eventId, ex.getMessage())
                )
                .then();
    }
}

