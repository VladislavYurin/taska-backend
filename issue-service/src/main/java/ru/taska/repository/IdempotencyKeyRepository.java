package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.IdempotencyKey;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyKeyRepository extends ReactiveCrudRepository<IdempotencyKey, UUID> {

    Mono<IdempotencyKey> findByUserIdAndKey(UUID userId, String key);

    @Modifying
    @Query("DELETE FROM taska.idempotency_keys WHERE expires_at <= now()")
    Mono<Long> deleteByExpiresAtBefore();

}
