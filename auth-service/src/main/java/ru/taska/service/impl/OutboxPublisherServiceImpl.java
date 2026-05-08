package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.OutboxPublisherService;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherServiceImpl implements OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        outboxEventRepository.findUnpublishedEvents()
                .flatMap(this::publish)
                .subscribe();
    }

    /**
     * Конвертирует future в моно и пытается отправить сообщение
     */
    private Mono<Void> publish(OutboxEvent event) {
        return Mono.fromFuture(kafkaTemplate.send("user.events",
                        event.getAggregateId().toString(),
                        event.getPayload().toString()))
                .flatMap(result -> handleSuccess(event))
                .onErrorResume(error -> handleFailure(event, error));
    }

    /**
     * Если отправка успешна, устанавливает published_at = now()
     */
    private Mono<Void> handleSuccess(OutboxEvent event) {
        return outboxEventRepository.save(
                event.toBuilder()
                        .publishedAt(Instant.now())
                        .build()
        ).then();
    }

    /**
     * Если ошибка, увеличивает attempts, пишет last_error_message
     */
    private Mono<Void> handleFailure(OutboxEvent event, Throwable error) {
        log.error("Failed to publish event {}: {}", event.getId(), error.getMessage());

        return outboxEventRepository.save(
                event.toBuilder()
                        .attempts(event.getAttempts() + 1)
                        .lastErrorMessage(error.getMessage())
                        .build()
        ).then();
    }
}