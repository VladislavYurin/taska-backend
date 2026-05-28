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
    Mono<InviteToken> findByTokenHash(String hash);

    @Modifying
    @Query("UPDATE taska.invite_tokens " +
            "SET used_at = NOW() " +
            "WHERE token_hash= :tokenHash AND used_at IS NULL AND expires_at > NOW()")
    Mono<Integer> markTokenAsUsedIfValid(String tokenHash);

}