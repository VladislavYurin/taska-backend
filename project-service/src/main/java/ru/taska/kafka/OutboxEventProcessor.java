package ru.taska.kafka;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.KafkaProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaProperties kafkaProperties;
    private final OutboxEventPublisher publisher;

    /**
     * Обрабатывает очередную пачку событий transactional outbox.
     *
     * <p>Берет события из таблицы outbox в БД и публикует последовательно в порядке получения.</p>
     */
    public Mono<Void> processOutboxEvents() {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        return outboxEventRepository.findUnpublished(kafkaProperties.outbox().batchSize())
                                    .concatMap(event -> processEvent(event, success, failure))
                                    .then()
                                    .doFinally(signal -> {                                       // сводный лог здесь
                                        int ok = success.get();
                                        int failed = failure.get();
                                        if (ok + failed > 0) {
                                            log.info("Outbox обработан: Взято = {}, Отправлено = {}, Ошибок = {}",
                                                     ok + failed, ok, failed);
                                        }
                                    });
    }

    /**
     * Публикует событие в Kafka и помечает его как успешно обработанное.
     *
     * <p>В случае ошибки запускает механизм обработки неудачной публикации.</p>
     */
    private Mono<Void> processEvent(
            OutboxEvent event,
            AtomicInteger success,
            AtomicInteger failure) {
        return publisher.publish(event)
                        .then(Mono.defer(() ->
                                                 outboxEventRepository.markAsPublished(
                                                         event.getId(),
                                                         Instant.now()
                                                 ))
                        )
                        .doOnSuccess(rows -> {
                                         success.incrementAndGet();
                                     }
                        )
                        .then()
                        .onErrorResume(ex -> {
                            failure.incrementAndGet();
                            return handleProcessingError(event, ex);
                        });
    }

    /**
     * Обрабатывает застрявшие события (в статусе 'PROCESSING') дольше указанного времени.
     */
    public Mono<Void> processStuckEvents() {
        return Mono.defer(() -> {
                       Instant threshold = Instant.now()
                                                  .minusSeconds(kafkaProperties.outbox().processingTimeout().toSeconds());

                       return outboxEventRepository.resetStuckProcessingEvents(threshold)
                                                   .doOnSuccess(count -> {
                                                       if (count != null && count > 0) {
                                                           log.warn(
                                                                   "Recovered stuck outbox events: count={}",
                                                                   count
                                                           );
                                                       } else {
                                                           log.trace("No stuck outbox events detected");
                                                       }
                                                   });
                   })
                   .then();
    }

    /**
     * Обрабатывает ошибку публикации события.
     *
     * <p>Если количество попыток достигло максимально допустимого значения,
     * событие переводится в состояние 'FAILED'.
     * Иначе увеличивается счётчик неудачных попыток.</p>
     */
    private Mono<Void> handleProcessingError(OutboxEvent event, Throwable ex) {
        log.error(
                "Failed to publish event: id={}, aggregateId={}, eventType={}",
                event.getId(), event.getAggregateId(), event.getEventType(), ex
        );
        int currentAttempts = event.getAttempts() == null ? 0 : event.getAttempts();

        if (currentAttempts + 1 >= kafkaProperties.outbox().maxAttempts()) {
            return outboxEventRepository.markAsFailed(event.getId(), ex.getMessage())
                                        .doOnSuccess(rows ->
                                                             log.warn(
                                                                     "Event marked as FAILED: id={}, attempts={}",
                                                                     event.getId(),
                                                                     event.getAttempts()
                                                             )
                                        )
                                        .then();
        }

        return outboxEventRepository.incrementAttempts(event.getId(), ex.getMessage())
                                    .doOnSuccess(rows ->
                                                         log.warn(
                                                                 "Retry scheduled for event: id={}, attempts={}/{}",
                                                                 event.getId(),
                                                                 event.getAttempts(),
                                                                 kafkaProperties.outbox().maxAttempts()
                                                         )
                                    )
                                    .then();
    }

}
