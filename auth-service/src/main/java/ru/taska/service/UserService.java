package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.UserStatus;

import java.util.UUID;

public interface UserService {
    public Mono<Void> updateStatus(UUID userId, UserStatus newStatus);
}
