package ru.taska.domain;

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
 * История изменений задачи.
 *
 * <p>Фиксирует каждое значимое событие, произошедшее с задачей:
 * создание, изменение полей, назначение исполнителя, переход по статусам.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "issue_history", schema = "taska")
public class IssueHistory {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор задачи, к которой относится событие.
     */
    @Column("issue_id")
    private UUID issueId;

    /**
     * Тип события (CREATED, UPDATED, ASSIGNED, TRANSITIONED).
     */
    @Column("event_type")
    private IssueEventType eventType;

    /**
     * Идентификатор пользователя, инициировавшего событие.
     */
    @Column("actor_user_id")
    private UUID actorUserId;

    /**
     * Временная метка наступления события.
     */
    @Column("occurred_at")
    private Instant occurredAt;

    /**
     * Полезная нагрузка события в формате JSON.
     * Содержит детали изменения (старые/новые значения полей и т.п.).
     */
    @Column("payload")
    private JsonNode payload;
}
