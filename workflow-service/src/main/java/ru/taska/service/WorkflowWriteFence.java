package ru.taska.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** All project workflow writes take this transaction lock before touching workflow rows. */
@Service
@RequiredArgsConstructor
public class WorkflowWriteFence {
    private final DatabaseClient database;
    private final ru.taska.transport.grpc.project.WorkflowProjectOperationClient projects;

    public Mono<Void> lockProject(UUID project) {
        return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:project, 418))")
                .bind("project", project.toString()).map((row, metadata) -> true).one().then();
    }

    public Mono<Void> lockAndCheck(UUID project) {
        return lockProject(project).then(Mono.defer(() -> checkAvailable(project)));
    }

    /** Called only after taking 418; journal readers from issue-service never acquire that lock. */
    public Mono<Void> checkAvailable(UUID project) {
        return checkLocalAvailable(project).then(Mono.defer(() -> projects.gate(project)))
                .flatMap(gate -> gate.getActive() ? Mono.error(pending()) : Mono.empty());
    }

    public Mono<Void> checkLocalAvailable(UUID project) {
        return requireNoBindingChange(project).then(Mono.defer(() -> database.sql("SELECT EXISTS (SELECT 1 FROM taska.issue_type_move_workflow_pins WHERE project_id=:project AND released=false)")
                .bind("project", project).map(row -> row.get(0,Boolean.class)).one()))
                .flatMap(pinned -> pinned ? Mono.error(pending()) : Mono.empty());
    }

    public Mono<Void> requireNoBindingChange(UUID project) {
        return database.sql("SELECT EXISTS (SELECT 1 FROM taska.workflow_binding_changes WHERE project_id=:project AND reservation_released=false)")
                .bind("project",project).map(row -> row.get(0,Boolean.class)).one()
                .flatMap(active -> active ? Mono.error(pending()) : Mono.empty());
    }

    /** Graph-dependent reads fail closed while a prepared graph/binding view is being published. */
    public Mono<Void> requireReadable(UUID project) {
        return requireNoBindingChange(project).then(Mono.defer(() -> projects.gate(project)))
                .flatMap(gate -> gate.getActive() ? Mono.error(pending()) : Mono.empty());
    }

    public static void requireWritableRegistry(List<ProjectIssueTypeResponse> types) {
        if (types.stream().anyMatch(type -> !type.getPendingOperationId().isBlank())) throw pending();
    }

    static DomainException pending() {
        return new DomainException(DomainStatus.ABORTED, "A project issue-type operation is pending",
                Map.of("reason", "ISSUE_TYPE_OPERATION_PENDING"));
    }
}
