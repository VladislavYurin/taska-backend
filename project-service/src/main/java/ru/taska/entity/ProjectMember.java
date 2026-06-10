package ru.taska.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
@Table(value = "project_members", schema = "taska")
public class ProjectMember {

    @Column("project_id")
    private UUID projectId;

    @Column("user_id")
    private UUID userId;

    @Column("role")
    private ProjectRole role;

    @CreatedDate
    @Column("added_at")
    private Instant addedAt;

    @Column("added_by")
    private UUID addedBy;

}
