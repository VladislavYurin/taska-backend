package ru.taska.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.event.TaskaEvent;
import ru.taska.service.NotificationEventHandler;
import ru.taska.service.NotificationEventHandlerImpl;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer для топика {@code issue.events}.
 *
 * <p>Ответственен за десериализацию сообщения и делегирование обработки
 * в {@link NotificationEventHandlerImpl}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueEventsKafkaListener {

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
            log.error("Failed to process Kafka message: {}", message, ex);
        }
    }
}

