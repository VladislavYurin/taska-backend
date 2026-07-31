package ru.taska.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись журнала аудита административных действий.
 *
 * <p>Фиксирует контекст, инициатора и результат выполнения операций
 * изменения данных для обеспечения прослеживаемости и безопасности.</p>
 */
@Data
@NoArgsConstructor
@Table(name = "admin_audit_log", schema = "taska")
public class AuditLog {
    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор запроса для трассировки цепочки вызовов.
     */
    @Column("request_id")
    private String requestId;

    /**
     * Идентификатор пользователя, выполнившего действие.
     */
    @Column("actor_user_id")
    private UUID actorUserId;

    /**
     * Логин пользователя, выполнившего действие.
     */
    @Column("actor_login")
    private String actorLogin;

    /**
     * Роли пользователя на момент выполнения действия.
     */
    @Column("actor_roles")
    private JsonNode actorRoles;

    /**
     * Тип административного действия.
     */
    @Column("action")
    private String action;

    /**
     * Сервис, в котором был изменен объект.
     */
    @Column("target_service")
    private String targetService;

    /**
     * Таблица, содержащая измененный объект.
     */
    @Column("target_table")
    private String targetTable;

    /**
     * Идентификатор измененного объекта.
     */
    @Column("target_id")
    private String targetId;

    /**
     * Состояние объекта до изменения.
     */
    @Column("old_value")
    private JsonNode oldValue;

    /**
     * Состояние объекта после изменения.
     */
    @Column("new_value")
    private JsonNode newValue;

    /**
     * Причина выполнения административного действия.
     */
    @Column("reason")
    private String reason;

    /**
     * Временная метка создания записи.
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}
