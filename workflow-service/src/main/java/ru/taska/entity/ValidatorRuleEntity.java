package ru.taska.entity;

import lombok.*;
import tools.jackson.databind.JsonNode;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Правило валидации, связанное с переходом.
 * <p>
 * Правила выполняются workflow-service во время операции ValidateTransition.
 * Каждое правило определяет стратегию проверки (ruleType)
 * и при необходимости содержит конфигурацию в формате JSON.
 * В случае ошибки возвращается нарушение (errorCode + errorMessage).
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.validator_rules")
public class ValidatorRuleEntity {

    /**
     * Первичный ключ.
     */
    @Id
    private UUID id;

    /**
     * Идентификатор transition, к которому относится правило.
     */
    @Column("transition_id")
    private UUID transitionId;

    /**
     * Тип правила (стратегия валидации).
     */
    @Column("rule_type")
    private ValidatorType ruleType;

    /**
     * Конфигурация правила в формате JSONB.
     * Пример для REQUIRE_FIELD: {"field":"resolution"}.
     */
    @Column("config")
    private JsonNode config;

    /**
     * Машиночитаемый код ошибки, возвращаемый при нарушении правила.
     */
    @Column("error_code")
    private String errorCode;

    /**
     * Человекочитаемое сообщение об ошибке,
     * возвращаемое при нарушении правила.
     */
    @Column("error_message")
    private String errorMessage;

    /**
     * Определяет порядок выполнения правил в рамках одного перехода.
     * Меньшие значения выполняются раньше.
     */
    @Column("sort_order")
    private int sortOrder;

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
