package ru.taska.kafka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.KafkaTopicsProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.transport.kafka.OutboxEventProcessor;
import ru.taska.transport.kafka.OutboxEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private OutboxEventPublisher publisher;

    @Mock
    private KafkaTopicsProperties properties;

    @InjectMocks
    private OutboxEventProcessor processor;

    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .attempts(0)
                .build();

        var outboxProperties = new KafkaTopicsProperties.Outbox(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                100,
                5,
                Duration.ofSeconds(60)
        );

        Mockito.when(properties.outbox())
                .thenReturn(outboxProperties);
    }

    @Test
    @DisplayName("Успешная обработка события")
    void processOutboxEvents_shouldProcessEvent_whenSuccess() {
        Mockito.when(repository.findUnpublished(100))
                .thenReturn(Flux.just(event));

        Mockito.when(publisher.publish(event))
                .thenReturn(Mono.empty());

        Mockito.when(repository.markAsPublished(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.any(Instant.class))
                )
                .thenReturn(Mono.just(1));

        StepVerifier.create(processor.processOutboxEvents())
                .verifyComplete();

        Mockito.verify(repository)
                .findUnpublished(100);

        Mockito.verify(publisher)
                .publish(event);

        Mockito.verify(repository)
                .markAsPublished(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.any(Instant.class)
                );

        Mockito.verify(repository, Mockito.never())
                .markAsFailed(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString()
                );

        Mockito.verify(repository, Mockito.never())
                .incrementAttempts(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString()
                );
    }

    @Test
    @DisplayName("Делает retry при ошибке обработки, если attempts < max")
    void processOutboxEvents_shouldRetry_whenPublishFailedAndAttemptsLessThanMax() {
        Mockito.when(repository.findUnpublished(100))
                .thenReturn(Flux.just(event));

        Mockito.when(publisher.publish(event))
                .thenReturn(Mono.error(new RuntimeException("Kafka unavailable")));

        Mockito.when(repository.incrementAttempts(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.anyString())
                )
                .thenReturn(Mono.just(1));

        StepVerifier.create(processor.processOutboxEvents())
                .verifyComplete();

        Mockito.verify(repository)
                .incrementAttempts(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.contains("Kafka unavailable")
                );

        Mockito.verify(repository, Mockito.never())
                .markAsFailed(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString()
                );

        Mockito.verify(repository, Mockito.never())
                .markAsPublished(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()
                );
    }

    @Test
    @DisplayName("Помечает событие как 'FAILED', когда количество попыток достигает порог maxAttempts")
    void processOutboxEvents_shouldMarkAsFailed_whenPublishFailedAndAttemptsGreaterThanMax() {
        event.setAttempts(4);

        Mockito.when(repository.findUnpublished(100))
                .thenReturn(Flux.just(event));

        Mockito.when(publisher.publish(event))
                .thenReturn(Mono.error(new RuntimeException("Kafka unavailable")));

        Mockito.when(repository.markAsFailed(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.anyString())
                )
                .thenReturn(Mono.just(1));

        StepVerifier.create(processor.processOutboxEvents())
                .verifyComplete();

        Mockito.verify(repository)
                .markAsFailed(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.contains("Kafka unavailable")
                );

        Mockito.verify(repository, Mockito.never())
                .incrementAttempts(ArgumentMatchers.any(), ArgumentMatchers.any());

        Mockito.verify(repository, Mockito.never())
                .markAsPublished(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Успешная обработка нескольких событий")
    void processOutboxEvents_shouldProcessMultipleEvents_whenSuccess() {
        var event2 = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .attempts(0)
                .build();

        Mockito.when(repository.findUnpublished(100))
                .thenReturn(Flux.just(event, event2));

        Mockito.when(publisher.publish(ArgumentMatchers.any()))
                .thenReturn(Mono.empty());

        Mockito.when(repository.markAsPublished(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any())
                )
                .thenReturn(Mono.just(1));

        StepVerifier.create(processor.processOutboxEvents())
                .verifyComplete();

        Mockito.verify(publisher).publish(event);

        Mockito.verify(publisher).publish(event2);

        Mockito.verify(repository)
                .markAsPublished(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.any(Instant.class)
                );

        Mockito.verify(repository)
                .markAsPublished(
                        ArgumentMatchers.eq(event2.getId()),
                        ArgumentMatchers.any(Instant.class)
                );
    }

    @Test
    @DisplayName("Должен преобразовать null в 0 и вызвать метод incrementAttempts()")
    void processOutboxEvents_shouldCallIncrementAttempts_whenAttemptIsNull() {
        event.setAttempts(null);

        Mockito.when(repository.findUnpublished(ArgumentMatchers.anyInt()))
                .thenReturn(Flux.just(event));

        Mockito.when(publisher.publish(event))
                .thenReturn(Mono.error(new RuntimeException("Kafka unavailable")));

        Mockito.when(repository.incrementAttempts(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.anyString())
                )
                .thenReturn(Mono.just(1));

        StepVerifier.create(processor.processOutboxEvents())
                .verifyComplete();

        Mockito.verify(repository)
                .incrementAttempts(
                        ArgumentMatchers.eq(event.getId()),
                        ArgumentMatchers.contains("Kafka unavailable")
                );
    }

    @Test
    @DisplayName("Успешная обработка застрявших событий")
    void processStuckEvents_shouldRecoverStuckEvents() {
        Mockito.when(repository.resetStuckProcessingEvents(ArgumentMatchers.any(Instant.class)))
                .thenReturn(Mono.just(3));

        StepVerifier.create(processor.processStuckEvents())
                .verifyComplete();

        Mockito.verify(repository)
                .resetStuckProcessingEvents(ArgumentMatchers.any(Instant.class));
    }
}