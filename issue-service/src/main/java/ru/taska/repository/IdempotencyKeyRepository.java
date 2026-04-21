package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.IdempotencyKey;

import java.util.UUID;

public interface IdempotencyKeyRepository extends ReactiveCrudRepository<IdempotencyKey, UUID> {

    Mono<IdempotencyKey> findByUserIdAndKey(UUID userId, String key);
}
