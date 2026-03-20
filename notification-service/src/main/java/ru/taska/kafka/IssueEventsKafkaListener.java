package ru.taska.kafka;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.event.TaskaEvent;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer для топика {@code issue.events}.
 *
 * <p>Ответственен за десериализацию сообщения и делегирование обработки
 * в {@link NotificationEventHandler}.</p>
 */
@Component
@RequiredArgsConstructor
public class IssueEventsKafkaListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(IssueEventsKafkaListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationEventHandler eventHandler;

    @KafkaListener(topics = "issue.events", groupId = "notification-service")
    public void onMessage(String message) {
        try {
            TaskaEvent event = objectMapper.readValue(message, TaskaEvent.class);
            Mono<Void> processing = eventHandler.handle(event);
            // Блокируемся внутри consumer-потока, чтобы не потерять ошибки обработки.
            processing.block();
        } catch (Exception ex) {
            LOGGER.error("Failed to process Kafka message: {}", message, ex);
        }
    }
}

