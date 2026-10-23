package ru.taska.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.InviteToken;

@Repository
public interface InviteTokenRepository extends ReactiveCrudRepository<InviteToken, UUID> {

    Mono<InviteToken> findByUserId(UUID userId);

    @Query("SELECT * FROM taska.invite_tokens WHERE user_id = :userId ORDER BY created_at DESC, id DESC LIMIT 1")
    Mono<InviteToken> findLatestByUserId(UUID userId);

    @Modifying
    @Query("UPDATE taska.invite_tokens SET used_at = now() WHERE user_id = :userId AND used_at IS NULL")
    Mono<Integer> revokeUnusedForUser(UUID userId);
    Mono<InviteToken> findByTokenHash(String hash);

    @Modifying
    @Query("UPDATE taska.invite_tokens " +
            "SET used_at = NOW() " +
            "WHERE token_hash= :tokenHash AND used_at IS NULL AND expires_at > NOW()")
    Mono<Integer> markTokenAsUsedIfValid(String tokenHash);

}