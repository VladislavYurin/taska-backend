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

    // Revoking a tenant-bound token also retires its grant generation (TAS-463).
    @Query("""
            WITH revoked AS (UPDATE taska.personal_access_tokens SET revoked_at=:now,revoked_reason=:reason
            WHERE id=:id AND user_id=:userId AND revoked_at IS NULL RETURNING *),
            grants AS (UPDATE taska.integration_grants g SET revoked_at=:now,version=g.version+1
            FROM revoked r WHERE g.id=r.integration_grant_id AND g.revoked_at IS NULL)
            SELECT * FROM revoked
            """)
    Mono<PersonalAccessToken> revokeOwned(UUID id, UUID userId, Instant now, String reason);

    @Query("""
            WITH revoked AS (UPDATE taska.personal_access_tokens SET revoked_at=:now,revoked_reason=:reason
            WHERE user_id=:userId AND revoked_at IS NULL RETURNING *),
            grants AS (UPDATE taska.integration_grants g SET revoked_at=:now,version=g.version+1
            FROM revoked r WHERE g.id=r.integration_grant_id AND g.revoked_at IS NULL)
            SELECT * FROM revoked
            """)
    Flux<PersonalAccessToken> revokeAll(UUID userId, Instant now, String reason);

    @Modifying
    @Query("""
            UPDATE taska.personal_access_tokens SET last_used_at=:now WHERE id=:id AND revoked_at IS NULL
            AND expires_at>:now AND (last_used_at IS NULL OR last_used_at<:threshold)
            """)
    Mono<Integer> touchIfStale(UUID id, Instant now, Instant threshold);
}
