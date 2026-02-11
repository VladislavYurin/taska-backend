package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.credentials")
public class Credential {

    @Id
    @Column("id")
    private UUID id;

    @Column("user_id")
    private UUID userId;

    @Column("credential_type")
    private CredentialType credentialType;

    @Column("provider")
    private String provider;

    @Column("subject")
    private String subject;

    @Column("secret_hash")
    private String secretHash;

    @Column("algo")
    private HashingAlgorithm algo;

    @Column("meta")
    private String meta;

    @Column("failed_attempts")
    private Integer failedAttempts;

    @Column("last_failed_at")
    private Instant lastFailedAt;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}