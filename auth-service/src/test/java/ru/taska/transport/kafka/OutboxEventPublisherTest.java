package ru.taska.transport.kafka;

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
import ru.taska.config.OutboxConfig;
import ru.taska.entity.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    private static final String TOPIC = "project.events";

    @Mock
    private OutboxConfig config;

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
        Mockito.when(config.getTopic()).thenReturn(TOPIC);

        publisher = new OutboxEventPublisher(kafkaSender,config, mapper);

        event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("USER")
                .aggregateId(UUID.randomUUID())
                .eventType("UserInvited")
                .build();

        expectedJson = "{\"eventType\":\"UserInvited\"}";
    }

    @Nested
    class Publish{

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
        void shouldPropagateError_whenKafkaFails(){
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

        @Test
        @DisplayName("Должен пробрасывать ошибку при ошибке сериализации")
        void shouldPropagateError_whenMapperFails() {
            var expectedException = new RuntimeException("Serialization error");

            Mockito.when(mapper.toTaskaEventJsonAsString(event))
                    .thenThrow(expectedException);

            /// Не мокаем kafkaSender - ошибка произойдет до него

            StepVerifier.create(publisher.publish(event))
                    .expectErrorMatches(actualException -> actualException == expectedException)
                    .verify();

            Mockito.verify(mapper)
                    .toTaskaEventJsonAsString(event);
            Mockito.verifyNoInteractions(kafkaSender); /// Проверяем, что kafkaSender не вызывался
        }
    }
}
