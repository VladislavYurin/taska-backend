package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.Credential;
import ru.taska.domain.CredentialType;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface CredentialRepository extends ReactiveCrudRepository<Credential, UUID> {

    Mono<Credential> findByUserIdAndCredentialType(UUID userId, CredentialType credentialType);

    @Query("SELECT * FROM taska.credentials WHERE user_id = $1 AND credential_type = $2")
    Mono<Credential> findPasswordCredential(UUID userId, String credentialType);

    @Query("UPDATE taska.credentials SET failed_attempts = $1, last_failed_at = $2, locked_until = $3 WHERE id = $4")
    Mono<Integer> updateFailedAttempts(Integer attempts, Instant lastFailedAt, Instant lockedUntil, UUID credentialId);
}