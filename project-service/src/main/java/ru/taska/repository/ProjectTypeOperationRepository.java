package ru.taska.repository;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.ProjectTypeOperationGate;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Call mutations only under the project's row lock; closed identities are never reactivated. */
@Repository
@RequiredArgsConstructor
public class ProjectTypeOperationRepository {
    private final DatabaseClient db;
    public Mono<ProjectTypeOperationGate> active(UUID project) {
        return db.sql("SELECT id,kind,active FROM taska.project_type_operations WHERE project_id=:p AND active")
                .bind("p",project).map(row -> gate(row.get("id",UUID.class),row.get("kind",String.class),true)).one();
    }
    public Mono<ProjectTypeOperationGate> find(UUID project,UUID operation) {
        return db.sql("SELECT id,kind,active FROM taska.project_type_operations WHERE project_id=:p AND id=:id")
                .bind("p",project).bind("id",operation).map(row -> gate(row.get("id",UUID.class),row.get("kind",String.class),Boolean.TRUE.equals(row.get("active",Boolean.class)))).one();
    }
    public Mono<Void> requireFree(UUID project) { return active(project).flatMap(gate -> Mono.error(busy(gate))).then(); }
    public Mono<Void> reserve(UUID project,UUID operation,String kind) {
        return find(project,operation).flatMap(saved -> {
            if(!saved.getKind().equals(kind)||!saved.getActive()) return Mono.error(new DomainException(DomainStatus.ABORTED,
                    "Project type operation was already closed",Map.of("reason","PROJECT_TYPE_OPERATION_FINISHED")));
            return Mono.just(saved);
        }).switchIfEmpty(Mono.defer(()->active(project).flatMap(saved->Mono.<ProjectTypeOperationGate>error(busy(saved)))
                .switchIfEmpty(db.sql("INSERT INTO taska.project_type_operations(id,project_id,kind,active) VALUES(:id,:p,:kind,true)")
                        .bind("id",operation).bind("p",project).bind("kind",kind).then().thenReturn(gate(operation,kind,true))))).then();
    }
    /** Also stores a terminal receipt if a timed-out reserve has not arrived yet. */
    public Mono<Void> close(UUID project,UUID operation,String kind) {
        return db.sql("""
                INSERT INTO taska.project_type_operations(id,project_id,kind,active) VALUES(:id,:p,:kind,false)
                ON CONFLICT(id) DO UPDATE SET active=false
                  WHERE taska.project_type_operations.project_id=:p AND taska.project_type_operations.kind=:kind
                """).bind("id",operation).bind("p",project).bind("kind",kind).fetch().rowsUpdated()
                .flatMap(count->count==1?Mono.empty():Mono.error(new DomainException(DomainStatus.ABORTED,"Project type operation identity mismatch")));
    }
    private static ProjectTypeOperationGate gate(UUID id,String kind,boolean active) {
        return ProjectTypeOperationGate.newBuilder().setOperationId(id.toString()).setKind(kind).setActive(active).build();
    }
    public static DomainException busy(ProjectTypeOperationGate gate) {
        return new DomainException(DomainStatus.ABORTED,"Another project type operation is pending",
                Map.of("reason","PROJECT_TYPE_OPERATION_PENDING","operationId",gate.getOperationId()));
    }
}
