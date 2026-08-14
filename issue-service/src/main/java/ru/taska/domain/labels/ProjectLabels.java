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

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "project_labels",schema = "taska")
public class ProjectLabels {

    @Id
    @Column("id")
    private UUID id;

    @Column("project_id")
    private UUID projectId;

    @Column("name")
    private String name;

    @Column("color")
    private String color;

    @Column("created_by")
    private UUID createdBy;

    @Column("created_at")
    private Instant createdAt;

    @Column("deleted_at")
    private Instant deletedAt;
}
