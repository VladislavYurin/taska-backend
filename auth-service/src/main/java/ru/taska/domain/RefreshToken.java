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

/**
 * Refresh-токен пользователя (долгоживущая сессия).
 *
 * <p>Хранится только хэш токена. Поддерживает отзыв
 * и безопасную ротацию через {@code replacedBy}.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "refresh_tokens", schema = "taska")
public class RefreshToken {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор пользователя-владельца токена.
     */
    @Column("user_id")
    private UUID userId;

    /**
     * Хэш refresh-токена (сырой токен не хранится).
     */
    @Column("token_hash")
    private String tokenHash;

    /**
     * Время выдачи токена.
     */
    @Column("issued_at")
    private Instant issuedAt;

    /**
     * Время истечения срока действия токена.
     */
    @Column("expires_at")
    private Instant expiresAt;

    /**
     * Время отзыва токена (если отозван).
     */
    @Column("revoked_at")
    private Instant revokedAt;

    /**
     * Идентификатор нового токена при ротации.
     */
    @Column("replaced_by")
    private UUID replacedBy;

    /**
     * Временная метка создания записи (аудит).
     */
    @Column("created_at")
    private Instant createdAt;
}
