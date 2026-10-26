package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;
import ru.taska.util.DisplayNames;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserDisplayNameService {
    private final UserRepository users;
    public record Change(UUID userId, String previousDisplayName, String displayName) { }
    @Transactional
    public Mono<Change> updateByAdmin(UUID actorId, UUID targetId, String displayName) {
        return Mono.defer(() -> {
            String name = DisplayNames.normalize(displayName);
            return users.findById(actorId)
                    .filter(actor -> actor.getGlobalRole() == GlobalRole.GLOBAL_ADMIN
                            && (actor.getStatus() == UserStatus.ACTIVE || actor.getStatus() == UserStatus.LOCKED))
                    .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Global admin role is required")))
                    .then(Mono.defer(() -> users.findByIdForUpdate(targetId)))
                    .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                    .flatMap(user -> users.updateDisplayName(targetId, name)
                            .map(saved -> new Change(targetId, user.getDisplayName(), saved.getDisplayName())));
        });
    }
}
