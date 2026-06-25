package ru.taska.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import ru.taska.event.TaskaEvent;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer для топиков {@code issue.events}, {@code project.events}, {@code user.events}.
 *
 * <p>Ответственен за десериализацию сообщения и делегирование обработки
 * в {@link ru.taska.service.NotificationEventHandlerImpl}.</p>
 *
 * <p>Offset коммитится вручную только после успешной обработки.
 * Стратегия retry задаётся в {@link KafkaConsumerConfig}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskaEventsKafkaListener {

    private final ObjectMapper objectMapper;
    private final NotificationEventHandler eventHandler;

    @KafkaListener(topics = {"issue.events", "project.events", "user.events"}, groupId = "notification-service")
    public void onMessage(String message, Acknowledgment ack,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        TaskaEvent event = null;
        try {
            event = objectMapper.readValue(message, TaskaEvent.class);
            log.info("Processing event: topic={}, eventId={}, type={}", topic, event.id(), event.eventType());
            eventHandler.handle(event).block();
            ack.acknowledge();
            log.info("Event processed and offset committed: topic={}, eventId={}", topic, event.id());
        } catch (Exception ex) {
            String eventId = event != null ? String.valueOf(event.id()) : "unknown";
            log.error("Failed to process event: topic={}, eventId={}, error={}", topic, eventId, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }
}
