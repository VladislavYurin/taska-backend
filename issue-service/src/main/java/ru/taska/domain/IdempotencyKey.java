package ru.taska.domain;

import org.springframework.data.annotation.CreatedDate;
import tools.jackson.databind.JsonNode;
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
 * Ключ идемпотентности для защиты от дублей при ретраях во время создания или изменения задачи.
 *
 * <p>При повторном запросе с тем же {@code key} и {@code userId} сервис возвращает
 * сохранённый {@code response} вместо повторного выполнения операции.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "idempotency_keys", schema = "taska")
public class IdempotencyKey {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Клиентский ключ идемпотентности (передаётся в запросе).
     */
    @Column("key")
    private String key;

    /**
     * Идентификатор пользователя, от имени которого выполнен запрос.
     */
    @Column("user_id")
    private UUID userId;

    /**
     * Хэш тела запроса для обнаружения изменения параметров при повторном вызове.
     */
    @Column("request_hash")
    private String requestHash;

    /**
     * Сохранённый ответ на исходный запрос в формате JSON.
     */
    @Column("response")
    private JsonNode response;

    /**
     * Временная метка создания записи.
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка истечения срока действия ключа.
     */
    @Column("expires_at")
    private Instant expiresAt;
}
