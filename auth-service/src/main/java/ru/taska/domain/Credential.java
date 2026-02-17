package ru.taska.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Учётные данные пользователя.
 *
 * <p>Описывает способ аутентификации и хранения секретов.
 * Поддерживает как парольную аутентификацию, так и внешние провайдеры
 * (OIDC, OAuth, SAML), API-токены и WebAuthn.</p>
 *
 * <h3>Использование полей в зависимости от {@link CredentialType}:</h3>
 *
 * <ul>
 *   <li><b>PASSWORD</b>
 *     <ul>
 *       <li>{@code secretHash} — хэш пароля (обязательно);</li>
 *       <li>{@code algo} — алгоритм хэширования (BCRYPT/ARGON2);</li>
 *       <li>{@code provider}, {@code subject} — не используются (null);</li>
 *       <li>{@code failedAttempts}, {@code lockedUntil} — защита от перебора.</li>
 *     </ul>
 *   </li>
 *
 *   <li><b>OAUTH / OIDC / SAML</b>
 *     <ul>
 *       <li>{@code provider} — идентификатор провайдера (google, github и т.п.);</li>
 *       <li>{@code subject} — уникальный идентификатор пользователя у провайдера;</li>
 *       <li>{@code secretHash}, {@code algo} — не используются;</li>
 *       <li>{@code meta} — дополнительные данные провайдера (опционально).</li>
 *     </ul>
 *   </li>
 *
 *   <li><b>API_TOKEN</b>
 *     <ul>
 *       <li>{@code secretHash} — хэш API-токена;</li>
 *       <li>{@code algo} — может использоваться для указания алгоритма;</li>
 *       <li>{@code provider}, {@code subject} — не используются.</li>
 *     </ul>
 *   </li>
 *
 *   <li><b>WEBAUTHN</b>
 *     <ul>
 *       <li>Криптографические параметры могут храниться в {@code meta};</li>
 *       <li>{@code secretHash} не используется.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Сырые секреты (пароли, токены) в БД не хранятся —
 * сохраняется только их хэш.</p>
 *
 * <p>Поля {@code createdAt} и {@code updatedAt} управляются механизмом аудита.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.credentials")
public class Credential {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор пользователя, которому принадлежат учётные данные.
     */
    @Column("user_id")
    private UUID userId;

    /**
     * Тип учётных данных (PASSWORD, OIDC и др.).
     */
    @Column("credential_type")
    private CredentialType credentialType;

    /**
     * Идентификатор внешнего провайдера (google, github и т.п.).
     */
    @Column("provider")
    private String provider;

    /**
     * Уникальный идентификатор пользователя у внешнего провайдера.
     */
    @Column("subject")
    private String subject;

    /**
     * Хэш секрета (пароль, API-токен). Сырые значения не хранятся.
     */
    @Column("secret_hash")
    private String secretHash;

    /**
     * Алгоритм хэширования секрета (для PASSWORD/API_TOKEN).
     */
    @Column("algo")
    private HashingAlgorithm algo;

    /**
     * Дополнительные данные провайдера в формате JSON.
     */
    @Column("meta")
    private JsonNode meta;

    /**
     * Количество подряд неудачных попыток входа.
     */
    @Column("failed_attempts")
    private Integer failedAttempts;

    /**
     * Время последней неудачной попытки аутентификации.
     */
    @Column("last_failed_at")
    private Instant lastFailedAt;

    /**
     * Время, до которого аутентификация заблокирована.
     */
    @Column("locked_until")
    private Instant lockedUntil;

    /**
     * Временная метка создания записи (аудит).
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка последнего изменения записи (аудит).
     */
    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}