package ru.taska.kafka;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;
import ru.taska.config.props.KafkaProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.transport.kafka.OutboxEventPublisher;

import java.time.Duration;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    private static final String TOPIC = "project.events";

    @Mock
    private OutboxEventMapper mapper;

    @Mock
    private KafkaSender<String, String> kafkaSender;

    private OutboxEventPublisher publisher;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> captor;

    private OutboxEvent event;
    private String expectedJson;

    @BeforeEach
    void setUp() {
        KafkaProperties config = new KafkaProperties(
                new KafkaProperties.Topics("project.events"),
                new KafkaProperties.Outbox(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        100,
                        5,
                        Duration.ofMinutes(5)
                )
        );        publisher = new OutboxEventPublisher(config, mapper, kafkaSender);

        event = OutboxEvent.builder()
                           .id(UUID.randomUUID())
                           .aggregateType("PROJECT")
                           .aggregateId(UUID.randomUUID())
                           .eventType("ProjectCreated")
                           .build();

        expectedJson = "{\"eventType\":\"ProjectCreated\"}";
    }

    @Nested
    class Publish {

        @Test
        @DisplayName("Должен успешно опубликовать событие в Kafka")
        void shouldSendEventToKafka_whenSuccess() {
            @SuppressWarnings("unchecked")
            SenderResult<UUID> senderResult = Mockito.mock(SenderResult.class);

            Mockito.when(mapper.toTaskaEventJsonAsString(event))
                   .thenReturn(expectedJson);
            Mockito.when(kafkaSender.send(ArgumentMatchers.any(Mono.class)))
                   .thenReturn(Flux.just(senderResult));

            StepVerifier.create(publisher.publish(event))
                        .verifyComplete();

            Mockito.verify(mapper).toTaskaEventJsonAsString(event);
            Mockito.verify(kafkaSender).send(captor.capture());

            SenderRecord<String, String, UUID> record = captor.getValue().block();
            Assertions.assertThat(record).isNotNull();
            Assertions.assertThat(record.topic()).isEqualTo(TOPIC);
            Assertions.assertThat(record.key()).isEqualTo(event.getAggregateId().toString());
            Assertions.assertThat(record.value()).isEqualTo(expectedJson);
            Assertions.assertThat(record.correlationMetadata()).isEqualTo(event.getId());
        }

        @Test
        @DisplayName("Должен пробрасывать ошибку при сбое отправки в Kafka")
        void shouldPropagateError_whenKafkaFails() {
            RuntimeException expectedException = new RuntimeException("Kafka unavailable");

            Mockito.when(mapper.toTaskaEventJsonAsString(event))
                   .thenReturn(expectedJson);
            Mockito.when(kafkaSender.send(ArgumentMatchers.any(Mono.class)))
                   .thenReturn(Flux.error(expectedException));

            StepVerifier.create(publisher.publish(event))
                        .expectErrorMatches(actual -> actual == expectedException)
                        .verify();
        }

        @Test
        @DisplayName("Должен пробрасывать ошибку, когда SenderResult содержит exception")
        void shouldPropagateError_whenSenderResultHasException() {
            RuntimeException expectedException = new RuntimeException("delivery timeout");

            @SuppressWarnings("unchecked")
            SenderResult<UUID> senderResult = Mockito.mock(SenderResult.class);
            Mockito.when(senderResult.exception()).thenReturn(expectedException);

            Mockito.when(mapper.toTaskaEventJsonAsString(event))
                   .thenReturn(expectedJson);
            Mockito.when(kafkaSender.send(ArgumentMatchers.any(Mono.class)))
                   .thenReturn(Flux.just(senderResult));

            StepVerifier.create(publisher.publish(event))
                        .expectErrorMatches(actual -> actual == expectedException)
                        .verify();
        }
    }
}
