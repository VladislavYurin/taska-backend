package ru.taska.transport.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import ru.taska.config.props.KafkaTopicsProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;

import java.util.UUID;

/**
 * Компонент публикации outbox событий в Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private final KafkaSender<String, String> kafkaSender;
    private final KafkaTopicsProperties properties;
    private final OutboxEventMapper outboxEventMapper;

    /**
     * Публикует событие в Kafka.
     *
     * <p>Событие преобразуется в TaskaEvent, сериализуется в JSON и отправляется в Kafka.</p>
     */
    public Mono<Void> publish(OutboxEvent event) {
        return Mono.defer(() -> {
            String topic = properties.topics().issueEvents();
            String key = event.getAggregateId().toString();
            String payload = outboxEventMapper.toTaskaEventJsonAsString(event);

            log.debug(
                    "Publishing outbox event started: id={}, aggregateType={}, aggregateId={}, eventType={}, topic={}",
                    event.getId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    topic
            );

            ProducerRecord<String, String> producerRecord =
                    new ProducerRecord<>(topic, key, payload);

            SenderRecord<String, String, UUID> senderRecord =
                    SenderRecord.create(producerRecord, event.getId());

            return kafkaSender
                    .send(Mono.just(senderRecord))
                    .flatMap(result -> verifyDelivery(result, event, topic))
                    .then();
        });
    }

    private Mono<Void> verifyDelivery(
            SenderResult<UUID> result,
            OutboxEvent event,
            String topic
    ) {
        if (result.exception() != null) {
            log.error("Outbox event delivery failed");

            return Mono.error(result.exception());
        }

        log.debug("Outbox event delivery confirmed: id={}, topic={}, partition={}, offset={}",
                event.getId(),
                topic,
                result.recordMetadata().partition(),
                result.recordMetadata().offset()
        );

        return Mono.empty();
    }

}
