package ru.taska.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.WorkflowBindingIntent;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectIssueTypeRepository;
import ru.taska.repository.ProjectTypeOperationRepository;

@Service
@RequiredArgsConstructor
public class WorkflowTypeReservationTransactions {
    private final ProjectIssueTypeRepository types;
    private final ProjectTypeOperationRepository operations;
    private final ProjectWriteGuard writeGuard;
    private final DatabaseClient db;

    @Transactional
    public Mono<Void> reserve(WorkflowBindingIntent intent) {
        UUID project=UUID.fromString(intent.getProjectId()),operation=UUID.fromString(intent.getOperationId()),actor=UUID.fromString(intent.getActorUserId());
        if(!intent.getState().equals("PENDING"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow operation is not pending"));
        if(intent.getMovesList().stream().map(move->move.getCode()).distinct().count()!=intent.getMovesCount())
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Duplicate type generation in workflow intent"));
        return lock(project).then(types.lockActorRole(project,actor)).filter(role->role==ProjectRole.ADMIN)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Project ADMIN role is required")))
                .then(writeGuard.check(project,actor)).then(operations.reserve(project,operation,"WORKFLOW_BINDINGS"))
                .thenMany(Flux.fromIterable(intent.getMovesList()).concatMap(move->types.find(project,move.getCode())
                        .filter(type->move.getGeneration().equals(type.generation().toString())
                                &&(type.pendingOperationId()==null||type.pendingOperationId().equals(operation)))
                        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Issue type lifecycle changed",
                                Map.of("reason","ISSUE_TYPES_CHANGED"))))
                        .flatMap(type->db.sql("UPDATE taska.project_issue_types SET pending_operation_id=:op WHERE project_id=:p AND code=:code AND generation=:generation")
                                .bind("op",operation).bind("p",project).bind("code",move.getCode()).bind("generation",type.generation()).then())))
                .then();
    }
    @Transactional
    public Mono<Void> release(WorkflowBindingIntent intent) {
        if(!List.of("COMPLETED","CANCELLED").contains(intent.getState()))return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Workflow operation is not terminal"));
        UUID project=UUID.fromString(intent.getProjectId()),operation=UUID.fromString(intent.getOperationId());
        return lock(project).then(operations.close(project,operation,"WORKFLOW_BINDINGS"))
                .then(db.sql("UPDATE taska.project_issue_types SET pending_operation_id=NULL WHERE project_id=:p AND pending_operation_id=:op")
                        .bind("p",project).bind("op",operation).then());
    }
    private Mono<Void> lock(UUID project) {
        return types.lockProject(project).switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found"))).then();
    }
}
