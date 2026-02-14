package ru.taska.domain;

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
 * Определение workflow (ориентированный граф статусов и переходов).
 * <p>
 * Представляет переиспользуемую конфигурацию,
 * описывающую допустимые переходы между статусами
 * и применяемые к ним правила валидации.
 * Привязывается к (projectId, issueType) через таблицу workflow_bindings.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.workflows")
public class Workflow {

    /**
     * Первичный ключ.
     */
    @Id
    private UUID id;

    /**
     * Отображаемое имя workflow в UI.
     */
    @Column("name")
    private String name;

    /**
     * Версия workflow.
     * Может использоваться для эволюции конфигурации
     * и обеспечения обратной совместимости.
     */
    @Column("version")
    private int version;

    /**
     * Включение/выключение workflow.
     * Позволяет отключить workflow без удаления записи.
     */
    @Column("is_active")
    private boolean active;

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
