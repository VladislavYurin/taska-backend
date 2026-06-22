package ru.taska.kafka;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;
import ru.taska.config.props.KafkaTopicsProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.transport.kafka.OutboxEventPublisher;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Mock
    private KafkaTopicsProperties properties;

    @Mock
    private OutboxEventMapper mapper;

    @InjectMocks
    private OutboxEventPublisher publisher;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> captor;

    private OutboxEvent event;
    private String expectedJson;

    @BeforeEach
    void setUp() {
        event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("issue")
                .aggregateId(UUID.randomUUID())
                .eventType("IssueCreated")
                .payload(Mockito.mock(JsonNode.class))
                .build();

        expectedJson = """
                {"id":"%s","aggregateType":"issue","aggregateId":"%s","eventType":"IssueCreated","payload":{}}
                """.formatted(event.getId(), event.getAggregateId());

        var topics = new KafkaTopicsProperties.Topics("issue.events");

        Mockito.when(properties.topics())
                .thenReturn(topics);

        Mockito.when(mapper.toTaskaEventJsonAsString(event))
                .thenReturn(expectedJson);
    }

    @Test
    @DisplayName("Должен успешно опубликовать событие в Kafka")
    void publish_shouldSendEventToKafka_whenSuccess() {
        SenderResult<UUID> senderResult = Mockito.mock(SenderResult.class);
        RecordMetadata recordMetadata = Mockito.mock(RecordMetadata.class);

        Mockito.when(senderResult.recordMetadata())
                .thenReturn(recordMetadata);

        Mockito.when(kafkaSender.send(ArgumentMatchers.any(Mono.class)))
                .thenReturn(Flux.just(senderResult));

        StepVerifier.create(publisher.publish(event))
                .verifyComplete();

        Mockito.verify(mapper)
                .toTaskaEventJsonAsString(event);

        Mockito.verify(kafkaSender)
                .send(captor.capture());

        SenderRecord<String, String, UUID> record = captor.getValue().block();

        Assertions.assertThat(record)
                .isNotNull();

        Assertions.assertThat(record.topic())
                .isEqualTo("issue.events");

        Assertions.assertThat(record.key())
                .isEqualTo(event.getAggregateId().toString());

        Assertions.assertThat(record.value())
                .isEqualTo(expectedJson);

        Assertions.assertThat(record.correlationMetadata())
                .isEqualTo(event.getId());
    }

    @Test
    @DisplayName("Должен пробрасывать ошибку при неудачной отправке в Kafka")
    void publish_shouldPropagateError_whenKafkaFails() {
        var expectedException = new RuntimeException("Kafka unavailable");

        Mockito.when(kafkaSender.send(ArgumentMatchers.any(Mono.class)))
                .thenReturn(Flux.error(expectedException));

        StepVerifier.create(publisher.publish(event))
                .expectErrorMatches(actualException -> actualException == expectedException)
                .verify();

        Mockito.verify(mapper)
                .toTaskaEventJsonAsString(event);

        Mockito.verify(kafkaSender)
                .send(ArgumentMatchers.any(Mono.class));
    }

    @Test
    @DisplayName("Должен пробрасывать ошибку сериализации")
    void publish_shouldPropagateError_whenMapperFails() {
        var expectedException = new RuntimeException("Serialization error");

        Mockito.when(kafkaSender.send(ArgumentMatchers.any(Mono.class)))
                .thenReturn(Flux.error(expectedException));

        StepVerifier.create(publisher.publish(event))
                .expectErrorMatches(actualException -> actualException == expectedException)
                .verify();

        Mockito.verify(mapper)
                .toTaskaEventJsonAsString(event);

        Mockito.verifyNoMoreInteractions(kafkaSender);
    }
}