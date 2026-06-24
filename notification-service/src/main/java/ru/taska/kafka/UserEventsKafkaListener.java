package ru.taska.kafka;

import ru.taska.event.TaskaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer для топика {@code user.events}.
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
public class UserEventsKafkaListener {

    private final ObjectMapper objectMapper;
    private final NotificationEventHandler eventHandler;

    @KafkaListener(topics = "user.events", groupId = "notification-service")
    public void onMessage(String message, Acknowledgment ack) {
        TaskaEvent event = null;
        try {
            event = objectMapper.readValue(message, TaskaEvent.class);
            log.info("Processing user event: eventId={}, type={}", event.id(), event.eventType());
            eventHandler.handle(event).block();
            ack.acknowledge();
            log.info("User event processed and offset committed: eventId={}", event.id());
        } catch (Exception ex) {
            String eventId = event != null ? String.valueOf(event.id()) : "unknown";
            log.error("Failed to process user event: eventId={}, error={}", eventId, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }
}
