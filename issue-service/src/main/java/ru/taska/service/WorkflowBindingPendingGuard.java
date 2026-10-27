package ru.taska.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.ReactorWorkflowBindingJournalGrpc;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Lock-free workflow journal reads are safe while the caller holds issue project lock 433. */
@Service
@RequiredArgsConstructor
public class WorkflowBindingPendingGuard {
    private final DatabaseClient db;
    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    public Mono<Void> requireWritable(UUID project,Collection<IssueType> types) {
        if(types.isEmpty())return Mono.empty();
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM taska.workflow_binding_receipts r,
                  jsonb_array_elements(r.scope) AS s
                  WHERE r.project_id=:p AND r.state='APPLIED' AND NOT r.released AND s->>'code' IN (:codes)) AS busy
                """).bind("p",project).bind("codes",types.stream().map(IssueType::name).distinct().toList())
                .map(row->Boolean.TRUE.equals(row.get("busy",Boolean.class))).one()
                .flatMap(busy->busy?Mono.error(pending()):Mono.empty())
                .then(Mono.defer(()->ReactorWorkflowBindingJournalGrpc.newReactorStub(workflowStub.getChannel())
                        .withDeadlineAfter(5,TimeUnit.SECONDS).getPending(client.TypeOperationClients.project(project))))
                .flatMap(intent->{
                    if(intent.getOperationId().isBlank())return Mono.empty();
                    if(!intent.getProjectId().equals(project.toString()))return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Workflow journal identity mismatch"));
                    return intent.getMovesList().stream().anyMatch(move->types.stream().anyMatch(type->type.name().equals(move.getCode())))
                            ?Mono.error(pending()):Mono.empty();
                });
    }
    public static DomainException pending() {
        return new DomainException(DomainStatus.FAILED_PRECONDITION,"Workflow bindings are being changed",
                Map.of("reason","WORKFLOW_BINDINGS_PENDING"));
    }
}
