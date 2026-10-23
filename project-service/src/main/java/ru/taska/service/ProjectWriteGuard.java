package ru.taska.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.exception.ProjectArchived;

/** Called inside project-service transactions, before member rows are locked. */
@Component
@RequiredArgsConstructor
public class ProjectWriteGuard {
    private final DatabaseClient db;

    public Mono<Void> check(UUID project, UUID actor) {
        return db.sql("""
                SELECT p.archived_at IS NOT NULL AS archived, pm.user_id IS NOT NULL AS member
                FROM taska.projects p LEFT JOIN taska.project_members pm ON pm.project_id=p.id AND pm.user_id=:actor
                WHERE p.id=:project FOR SHARE OF p
                """).bind("project", project).bind("actor", actor)
                .map((r, m) -> new Access(Boolean.TRUE.equals(r.get("archived", Boolean.class)), Boolean.TRUE.equals(r.get("member", Boolean.class))))
                .one().switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found")))
                .flatMap(access -> !access.member() ? Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied"))
                        : access.archived() ? Mono.error(ProjectArchived.error()) : Mono.empty());
    }

    private record Access(boolean archived, boolean member) { }
}
