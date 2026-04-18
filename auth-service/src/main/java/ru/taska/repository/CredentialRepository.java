package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.Credential;
import ru.taska.entity.CredentialType;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface CredentialRepository extends ReactiveCrudRepository<Credential, UUID> {

    @Query("SELECT * FROM taska.credentials WHERE user_id = :userId AND credential_type = :credentialType")
    Mono<Credential> findByUserIdAndCredentialType(UUID userId, CredentialType credentialType);

    @Query("SELECT * FROM taska.credentials WHERE user_id = :userId AND credential_type = :credentialType")
    Mono<Credential> findPasswordCredential(UUID userId, String credentialType);

    @Query("UPDATE taska.credentials SET failed_attempts = :attempts, last_failed_at = :lastFailedAt, locked_until = :lockedUntil WHERE id = :credentialId")
    Mono<Integer> updateFailedAttempts(Integer attempts, Instant lastFailedAt, Instant lockedUntil, UUID credentialId);
}