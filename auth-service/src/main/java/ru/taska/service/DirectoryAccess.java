package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DirectoryAccess {
    private final UserRepository users;

    public Mono<User> requireActor(UUID actorId, boolean admin) {
        return users.findById(actorId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE || user.getStatus() == UserStatus.LOCKED)
                .filter(user -> !admin || user.getGlobalRole() == GlobalRole.GLOBAL_ADMIN)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));
    }
}
