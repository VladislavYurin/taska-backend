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
@Table("taska.refresh_tokens")
public class RefreshToken {

    @Id
    @Column("id")
    private UUID id;

    @Column("user_id")
    private UUID userId;

    @Column("token_hash")
    private String tokenHash;

    @Column("issued_at")
    private Instant issuedAt;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("revoked_at")
    private Instant revokedAt;

    @Column("replaced_by")
    private UUID replacedBy;

    @Column("created_at")
    private Instant createdAt;
}
