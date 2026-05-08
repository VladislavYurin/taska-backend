package ru.taska.service.impl;

import exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.UserStatus;
import ru.taska.repository.UserRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.service.UserService;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final OutboxEventService outboxEventService;

    /**
     * Изменяет статус пользователя и регистрирует событие в outbox.
     */
    @Transactional
    public Mono<Void> updateStatus(UUID userId, UserStatus newStatus) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new UserNotFoundException("User with id " + userId + " not found")))
                .flatMap(user -> {
                    user.setStatus(newStatus);
                    return userRepository.save(user)
                            .flatMap(saveUser -> outboxEventService.registerStatusChange(user, newStatus));
                })
                .doOnSuccess(event -> log.debug("Successfully updated status to {} for user {}", newStatus, userId))
                .then();
    }
}