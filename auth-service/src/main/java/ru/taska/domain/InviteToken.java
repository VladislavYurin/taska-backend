package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Одноразовый токен приглашения пользователя.
 *
 * <p>Используется для установки пароля при первичной активации
 * пользователя со статусом INVITED.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.invite_tokens")
public class InviteToken {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор пользователя, для которого создан токен.
     */
    @Column("user_id")
    private UUID userId;

    /**
     * Хэш токена (сырой токен в БД не хранится).
     */
    @Column("token_hash")
    private String tokenHash;

    /**
     * Временная метка создания записи (аудит).
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Время истечения срока действия токена.
     */
    @Column("expires_at")
    private Instant expiresAt;

    /**
     * Время фактического использования токена (если использован).
     */
    @Column("used_at")
    private Instant usedAt;
}
