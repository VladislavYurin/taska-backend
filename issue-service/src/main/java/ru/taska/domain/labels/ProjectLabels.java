package ru.taska.domain.labels;

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
 * Сущность, связывающая метки с проектами
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "project_labels",schema = "taska")
public class ProjectLabels {

    /**
     * Идентификатор связи метки и проекта
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор проекта
     */
    @Column("project_id")
    private UUID projectId;

    /**
     * Название метки
     */
    @Column("name")
    private String name;

    /**
     * Цвет метки в HEX формате (Пример: #FFFFFF)
     */
    @Column("color")
    private String color;

    /**
     * Идентификатор пользователя, кем создана метка
     */
    @Column("created_by")
    private UUID createdBy;

    /**
     * Временная метка создания записи
     */
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка мягкого удаления (null — запись активна).
     */
    @Column("deleted_at")
    private Instant deletedAt;
}