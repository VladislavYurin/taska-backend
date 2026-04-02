package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.event.TaskaEvent;

public interface NotificationEventHandler {
    Mono<Void> handle(TaskaEvent event);
}
