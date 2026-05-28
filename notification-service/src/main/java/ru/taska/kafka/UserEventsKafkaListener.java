package ru.taska.kafka;

import ru.taska.event.TaskaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventsKafkaListener {

    private final ObjectMapper objectMapper;
    private final NotificationEventHandler eventHandler;

    @KafkaListener(topics = "user.events", groupId = "notification-service")
    public void onMessage(String message) {
        try {
            TaskaEvent event = objectMapper.readValue(message, TaskaEvent.class);
            log.info("Received user event: type={}, aggregateId={}", event.eventType(), event.aggregateId());
            Mono<Void> processing = eventHandler.handle(event);
            processing.block();
        } catch (Exception ex) {
            log.error("Failed to process Kafka message: {}", message, ex);
        }
    }
}