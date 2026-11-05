package ru.taska.projectstorage;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.ProjectTypeOperationGate;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Call mutations only under the project's row lock; closed identities are never reactivated. */
@RequiredArgsConstructor(access = lombok.AccessLevel.PACKAGE)
public class ProjectTypeOperationRepository {
    private final DatabaseClient db;
    private final UUID tenant;
    private final java.util.function.Supplier<RecoveryStamp> authority;
    public Mono<ProjectTypeOperationGate> active(UUID project) {
        return sql("SELECT id,kind,active FROM taska.project_type_operations WHERE tenant_id=:tenant AND project_id=:p AND active")
                .bind("p",project).map(row -> gate(row.get("id",UUID.class),row.get("kind",String.class),true)).one();
    }
    public Mono<ProjectTypeOperationGate> find(UUID project,UUID operation) {
        return sql("SELECT id,kind,active FROM taska.project_type_operations WHERE tenant_id=:tenant AND project_id=:p AND id=:id")
                .bind("p",project).bind("id",operation).map(row -> gate(row.get("id",UUID.class),row.get("kind",String.class),Boolean.TRUE.equals(row.get("active",Boolean.class)))).one();
    }
    public Mono<Void> requireFree(UUID project) { return active(project).flatMap(gate -> Mono.error(busy(gate))).then(); }
    public Mono<Void> reserve(UUID project,UUID operation,String kind) {
        return find(project,operation).flatMap(saved -> {
            if(!saved.getKind().equals(kind)||!saved.getActive()) return Mono.error(new DomainException(DomainStatus.ABORTED,
                    "Project type operation was already closed",Map.of("reason","PROJECT_TYPE_OPERATION_FINISHED")));
            return Mono.just(saved);
        }).switchIfEmpty(Mono.defer(()->active(project).flatMap(saved->Mono.<ProjectTypeOperationGate>error(busy(saved)))
                .switchIfEmpty(insertSql("INSERT INTO taska.project_type_operations(tenant_id,id,project_id,kind,active,grant_id,actor_auth_version,membership_version) VALUES(:tenant,:id,:p,:kind,true,:grant,:authVersion,:memberVersion)")
                        .bind("id",operation).bind("p",project).bind("kind",kind).then().thenReturn(gate(operation,kind,true))))).then();
    }
    /** Also stores a terminal receipt if a timed-out reserve has not arrived yet. */
    public Mono<Void> close(UUID project,UUID operation,String kind) {
        return insertSql("""
                INSERT INTO taska.project_type_operations(tenant_id,id,project_id,kind,active,grant_id,actor_auth_version,membership_version) VALUES(:tenant,:id,:p,:kind,false,:grant,:authVersion,:memberVersion)
                ON CONFLICT(tenant_id,id) DO UPDATE SET active=false
                  WHERE taska.project_type_operations.tenant_id=:tenant AND taska.project_type_operations.project_id=:p AND taska.project_type_operations.kind=:kind
                """).bind("id",operation).bind("p",project).bind("kind",kind).fetch().rowsUpdated()
                .flatMap(count->count==1?Mono.empty():Mono.error(new DomainException(DomainStatus.ABORTED,"Project type operation identity mismatch")));
    }
    private static ProjectTypeOperationGate gate(UUID id,String kind,boolean active) {
        return ProjectTypeOperationGate.newBuilder().setOperationId(id.toString()).setKind(kind).setActive(active).build();
    }
    public static DomainException busy(ProjectTypeOperationGate gate) {
        return new DomainException(DomainStatus.ABORTED,"Another project type operation is pending",
                Map.of("reason","ISSUE_TYPE_OPERATION_PENDING","operationId",gate.getOperationId()));
    }
    private DatabaseClient.GenericExecuteSpec sql(String statement) {
        return db.sql(statement).bind("tenant", tenant);
    }
    private DatabaseClient.GenericExecuteSpec insertSql(String statement) {
        return authority.get().bind(sql(statement));
    }
}
