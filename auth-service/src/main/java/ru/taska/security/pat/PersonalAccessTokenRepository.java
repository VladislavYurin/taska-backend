package ru.taska.security.pat;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PersonalAccessTokenRepository extends ReactiveCrudRepository<PersonalAccessToken, UUID> {
    Mono<PersonalAccessToken> findByTokenHash(String hash);

    @Query("SELECT * FROM taska.personal_access_tokens WHERE user_id=:userId AND revoked_at IS NULL ORDER BY created_at DESC,id DESC")
    Flux<PersonalAccessToken> listOwned(UUID userId);

    @Query("SELECT count(*) FROM taska.personal_access_tokens WHERE user_id=:userId AND revoked_at IS NULL AND expires_at>:now")
    Mono<Long> countActive(UUID userId, Instant now);

    @Query("""
            UPDATE taska.personal_access_tokens SET revoked_at=:now,revoked_reason=:reason
            WHERE id=:id AND user_id=:userId AND revoked_at IS NULL RETURNING *
            """)
    Mono<PersonalAccessToken> revokeOwned(UUID id, UUID userId, Instant now, String reason);

    @Query("""
            UPDATE taska.personal_access_tokens SET revoked_at=:now,revoked_reason=:reason
            WHERE user_id=:userId AND revoked_at IS NULL RETURNING *
            """)
    Flux<PersonalAccessToken> revokeAll(UUID userId, Instant now, String reason);

    @Modifying
    @Query("""
            UPDATE taska.personal_access_tokens SET last_used_at=:now WHERE id=:id AND revoked_at IS NULL
            AND expires_at>:now AND (last_used_at IS NULL OR last_used_at<:threshold)
            """)
    Mono<Integer> touchIfStale(UUID id, Instant now, Instant threshold);
}
