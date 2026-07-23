package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.UserAvatar;

import java.util.UUID;

@Repository
public interface UserAvatarRepository extends ReactiveCrudRepository<UserAvatar, UUID> {

    Mono<UserAvatar> findByUserId(UUID userId);
}
