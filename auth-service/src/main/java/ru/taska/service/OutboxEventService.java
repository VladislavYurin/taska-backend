package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.User;
import ru.taska.domain.UserStatus;

public interface OutboxEventService {
    public Mono<OutboxEvent> registerStatusChange(User user, UserStatus status);
}
