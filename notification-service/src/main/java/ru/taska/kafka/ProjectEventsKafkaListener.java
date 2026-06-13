package ru.taska.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.event.TaskaEvent;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectEventsKafkaListener {

    private final ObjectMapper objectMapper;
    private final NotificationEventHandler eventHandler;

    @KafkaListener(topics = "${kafka.topics.project-events:project.events}", groupId = "notification-service")
    public void onProjectEvent(String message) {
        try {
            log.debug("Поступило событие ProjectEvent");
            TaskaEvent event = objectMapper.readValue(message, TaskaEvent.class);
            Mono<Void> processing = eventHandler.handle(event);
            processing.block();
        } catch (Exception ex) {
            log.error("Failed to process Kafka message: {}", message, ex);
        }
    }

}
