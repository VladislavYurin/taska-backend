package ru.taska.repository;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.InviteToken;

@Repository
public interface InviteTokenRepository extends ReactiveCrudRepository<InviteToken, UUID> {

    Mono<InviteToken> findByUserId(UUID userId);
    Mono<InviteToken> findByTokenHash(String hash);

}