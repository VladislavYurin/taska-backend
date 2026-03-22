package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.RefreshToken;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, UUID> {

    Mono<RefreshToken> findByTokenHash(String tokenHash);

    @Query("SELECT * FROM taska.refresh_tokens WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > $2")
    Mono<RefreshToken> findValidToken(String tokenHash, Instant now);

    @Query("UPDATE taska.refresh_tokens SET revoked_at = $1, replaced_by = $2 WHERE id = $3")
    Mono<Integer> revokeToken(Instant revokedAt, UUID replacedBy, UUID tokenId);
}
