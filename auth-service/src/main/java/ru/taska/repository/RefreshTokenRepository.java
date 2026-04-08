package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.RefreshToken;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, UUID> {


    @Query("SELECT * FROM taska.refresh_tokens WHERE token_hash = :tokenHash")
    Mono<RefreshToken> findByTokenHash(String tokenHash);

    @Query("SELECT * FROM taska.refresh_tokens WHERE token_hash = :tokenHash AND revoked_at IS NULL AND expires_at > :now")
    Mono<RefreshToken> findValidToken(String tokenHash, Instant now);

    @Query("UPDATE taska.refresh_tokens SET revoked_at = :revokedAt  WHERE id = :tokenId")
    Mono<Integer> revokeToken(Instant revokedAt,  UUID tokenId);
}
