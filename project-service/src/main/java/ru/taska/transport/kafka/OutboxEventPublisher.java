package ru.taska.transport.kafka;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import ru.taska.config.props.KafkaProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private final KafkaProperties kafkaProperties;
    private final OutboxEventMapper outboxEventMapper;
    private final KafkaSender<String, String> kafkaSender;

    public Mono<Void> publish(OutboxEvent event) {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                kafkaProperties.topics().projectEvents(),
                event.getAggregateId().toString(),
                outboxEventMapper.toTaskaEventJsonAsString(event)
        );

        SenderRecord<String, String, UUID> senderRecord = SenderRecord.create(
                producerRecord,
                event.getId()
        );

        return kafkaSender.send(Mono.just(senderRecord))
                          .next()
                          .flatMap(result -> {
                                       if (result.exception() != null) {
                                           return Mono.error(result.exception());
                                       }
                                       return Mono.empty();
                                   }
                          )
                          .then();
    }

}
