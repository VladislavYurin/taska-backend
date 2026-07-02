package ru.taska.publisher;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.UUID;

/**
 * Абстрактный класс для публикации outbox событий в kafka
 *
 * параметр <T> - тип outbox события, специфичен для каждого сервиса
 */
@Slf4j
public abstract class AbstractOutboxEventPublisher<T> {
    private final KafkaSender<String,String> kafkaSender;

    protected AbstractOutboxEventPublisher(KafkaSender<String, String> kafkaSender) {
        this.kafkaSender = kafkaSender;
    }
    /**
     * Отправление событий в kafka:
     * Общая логика:
     * Получение топика -
     * Получение ключа
     * Мапинг события в json
     * Отправление события в kafka
     * Проверка результата доставки
     *
     * event - событие для отправки
     */

    public final Mono<Void> publish(T event) {
        if(event == null) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Event cannot be null"));
        }
        return Mono.defer(()->{
            String topic = getTopic(event);
            String key = getKey(event);
            String payload = converToJsonFromString(event);
            UUID correlationId = getCorrelationId(event);

            log.debug(
                    "Publishing outbox event : id={}, topic={} ,key={}",
                    correlationId,
                    topic,
                    key
            );

            ProducerRecord<String,String> producerRecord = new ProducerRecord<>(topic,key,payload);

            SenderRecord<String, String, UUID> senderRecord = SenderRecord.create(producerRecord,correlationId);

            return kafkaSender
                    .send(Mono.just(senderRecord))
                    .next()
                    .flatMap(result -> verifyDelivery(result,event,topic));
        });
    }


    /**
     * Проверка успешности отправки
     *
     * Логирует успех или ошибку.
     * В случае ошибки возвращает Mono.error
     */

    private Mono<Void> verifyDelivery(SenderResult<UUID> result, T event, String topic) {
        if (result.exception() != null) {
            log.error("Outbox event delivery failed: id={}", result.correlationMetadata());
            return Mono.error(result.exception());
        }
        if (result.recordMetadata() != null) {
            log.debug("Outbox event delivery confirmed: id={}, partition={}, offset={}",
                    result.correlationMetadata(),
                    result.recordMetadata().partition(),
                    result.recordMetadata().offset()
            );
        }
        else {
            log.debug("Outbox event delivery confirmed: id={} (metadata is unavailable)",
                    result.correlationMetadata());
        }
        return Mono.empty();
    }

    //================= Абстрактные методы, реализуются в каждом классе ===============//
    /**
     * Возвращает топик kafka для данного события
     */
    protected abstract String getTopic(T event);

    /**
     * Возвращает ключ для партиционирования в kafka (aggregateId)
     */
    protected abstract String getKey(T event);

    /**
     * Возвращает correlationId для сопоставления результата с событием [even.getId()]
     */
    protected abstract UUID getCorrelationId(T event);

    /**
     * Cериализует событие в JSON для отправки в kafka
     */
    protected abstract String converToJsonFromString(T event);
}
