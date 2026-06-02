package ru.taska.service;

import ru.taska.event.TaskaEvent;
import reactor.core.publisher.Mono;

public interface NotificationEventHandler {
    Mono<Void> handle(TaskaEvent event);
}
