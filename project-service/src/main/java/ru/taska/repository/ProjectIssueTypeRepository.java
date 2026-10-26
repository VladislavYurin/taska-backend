package ru.taska.repository;

import io.r2dbc.spi.Row;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.ProjectRole;

@Repository
@RequiredArgsConstructor
public class ProjectIssueTypeRepository {
    private final DatabaseClient db;

    public Flux<ProjectIssueType> list(UUID projectId) {
        return db.sql("SELECT * FROM taska.project_issue_types WHERE project_id=:project ORDER BY sort_order,code COLLATE \"C\"")
                .bind("project", projectId).map((r, m) -> read(r)).all();
    }

    public Flux<ProjectIssueType> listBatch(java.util.Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) return Flux.empty();
        return db.sql("SELECT * FROM taska.project_issue_types WHERE project_id IN (:projects) ORDER BY project_id,sort_order,code COLLATE \"C\"")
                .bind("projects", projectIds).map((r, m) -> read(r)).all();
    }

    public Mono<Void> reorder(UUID projectId, java.util.List<String> codes) {
        return db.sql("""
                UPDATE taska.project_issue_types t SET sort_order=ordered.ordinality * 10,updated_at=now()
                FROM unnest(CAST(:codes AS text[])) WITH ORDINALITY AS ordered(code,ordinality)
                WHERE t.project_id=:project AND t.code=ordered.code
                """).bind("codes", codes.toArray(String[]::new)).bind("project", projectId)
                .fetch().rowsUpdated().then();
    }

    public Mono<ProjectIssueType> find(UUID projectId, String code) {
        return db.sql("SELECT * FROM taska.project_issue_types WHERE project_id=:project AND code=:code")
                .bind("project", projectId).bind("code", code).map((r, m) -> read(r)).one();
    }

    public Mono<UUID> lockProject(UUID projectId) {
        return db.sql("SELECT id FROM taska.projects WHERE id=:project FOR UPDATE")
                .bind("project", projectId).map((r, m) -> r.get("id", UUID.class)).one();
    }

    public Mono<ProjectRole> lockActorRole(UUID projectId, UUID actor) {
        return db.sql("SELECT role FROM taska.project_members WHERE project_id=:project AND user_id=:actor FOR SHARE")
                .bind("project", projectId).bind("actor", actor)
                .map((r, m) -> ProjectRole.valueOf(r.get("role", String.class))).one();
    }

    public Mono<Void> seedDefaults(UUID projectId) {
        return db.sql("""
                INSERT INTO taska.project_issue_types(project_id,code,name,color,icon,sort_order)
                VALUES (:project,'TASK','Task','blue','check-square',10),
                       (:project,'BUG','Bug','red','bug',20),
                       (:project,'STORY','Story','green','book-open',30)
                """).bind("project", projectId).fetch().rowsUpdated().then();
    }

    public Mono<ProjectIssueType> insert(ProjectIssueType type) {
        return bind(db.sql("""
                INSERT INTO taska.project_issue_types(project_id,code,name,color,icon,sort_order)
                VALUES (:project,:code,:name,:color,:icon,:order) RETURNING *
                """), type).map((r, m) -> read(r)).one();
    }

    public Mono<ProjectIssueType> update(ProjectIssueType type) {
        return bind(db.sql("""
                UPDATE taska.project_issue_types SET name=:name,color=:color,icon=:icon,
                    sort_order=:order,updated_at=now() WHERE project_id=:project AND code=:code RETURNING *
                """), type).map((r, m) -> read(r)).one();
    }

    public Mono<Void> delete(UUID projectId, String code) {
        return db.sql("DELETE FROM taska.project_issue_types WHERE project_id=:project AND code=:code")
                .bind("project", projectId).bind("code", code).fetch().rowsUpdated().then();
    }

    public Mono<Void> syncLegacySettings(UUID projectId, UUID actor) {
        return db.sql("""
                INSERT INTO taska.project_settings(project_id,settings,updated_by)
                SELECT :project,jsonb_build_object(
                    'allowedIssueTypes',jsonb_agg(code ORDER BY sort_order,code COLLATE "C"),
                    'defaultIssueType',(array_agg(code ORDER BY sort_order,code COLLATE "C"))[1]),:actor
                FROM taska.project_issue_types WHERE project_id=:project
                ON CONFLICT(project_id) DO UPDATE SET settings=taska.project_settings.settings || EXCLUDED.settings,
                    updated_at=now(),updated_by=EXCLUDED.updated_by
                """).bind("project", projectId).bind("actor", actor).fetch().rowsUpdated().then();
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec sql, ProjectIssueType t) {
        return sql.bind("project", t.projectId()).bind("code", t.code()).bind("name", t.name())
                .bind("color", t.color()).bind("icon", t.icon()).bind("order", t.sortOrder());
    }

    private ProjectIssueType read(Row row) {
        return new ProjectIssueType(row.get("project_id", UUID.class), row.get("code", String.class),
                row.get("name", String.class), row.get("color", String.class), row.get("icon", String.class),
                row.get("sort_order", Integer.class), row.get("created_at", OffsetDateTime.class).toInstant(),
                row.get("updated_at", OffsetDateTime.class).toInstant(), row.get("generation", UUID.class));
    }
}
