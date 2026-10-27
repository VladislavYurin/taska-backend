package ru.taska.service;

import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.TypeOperationOutcome;
import ru.taska.api.registry.v1.TypeOperationState;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.domain.WorkflowBindingChange;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.WorkflowBindingChangeRepository;
import ru.taska.transport.grpc.issue.WorkflowBindingIssueClient;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

/** Durable forward completion; an APPLIED issue receipt is never undone by a later workflow outage. */
@Service
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@RequiredArgsConstructor
@Slf4j
public class WorkflowBindingCoordinator {
    private final WorkflowBindingTransactions transactions;
    private final WorkflowBindingChangeRepository journal;
    private final WorkflowProjectOperationClient projects;
    private final WorkflowBindingIssueClient issues;
    private static final Set<DomainStatus> REJECTED=Set.of(DomainStatus.INVALID_ARGUMENT,DomainStatus.FAILED_PRECONDITION,
            DomainStatus.PERMISSION_DENIED,DomainStatus.UNAUTHENTICATED,DomainStatus.NOT_FOUND,
            DomainStatus.ALREADY_EXISTS,DomainStatus.ABORTED);

    public Mono<ProjectWorkflow> create(Header header, UUID actor, WorkflowCreationDto dto) {
        return transactions.create(header,actor,dto).flatMap(this::drive);
    }
    public Mono<ProjectWorkflow> replace(Header header, UUID project, UUID workflow, UUID actor, Integer version,
            String name, List<String> types, Map<String,String> mappings) {
        return transactions.replace(header,project,workflow,actor,version,name,types,mappings).flatMap(this::drive);
    }
    public Mono<ProjectWorkflow> drive(WorkflowBindingChange item) {
        return switch(item.state()) {
            case "COMPLETED" -> release(item).then(Mono.fromCallable(() -> ProjectWorkflow.parseFrom(item.result())));
            case "CANCELLED" -> release(item).then(Mono.error(rejection(item)));
            case "CANCELLING" -> issues.cancel(item.project(),item.id()).onErrorMap(e -> pending(item))
                    .flatMap(result -> finish(item,result));
            case "PENDING" -> projects.reserve(item.project(),item.id()).then(issues.apply(item.project(),item.id()))
                    .onErrorResume(error -> {
                        DomainException failure=domain(error);
                        if (REJECTED.contains(failure.getStatus())) return Mono.just(TypeOperationOutcome.newBuilder()
                                .setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED).setRejectionStatus(failure.getStatus().name())
                                .setRejectionMessage(failure.getMessage()).putAllRejectionDetails(failure.getDetails()).build());
                        return Mono.error(pending(item));
                    }).flatMap(result -> finish(item,result));
            default -> Mono.error(new IllegalStateException("Invalid workflow binding state"));
        };
    }
    private Mono<ProjectWorkflow> finish(WorkflowBindingChange item, TypeOperationOutcome result) {
        if (result.getState()==TypeOperationState.TYPE_OPERATION_STATE_APPLIED)
            return transactions.complete(item).flatMap(saved -> release(item).thenReturn(saved)).onErrorMap(error -> pending(item));
        if (result.getState()==TypeOperationState.TYPE_OPERATION_STATE_CANCELLED)
            return transactions.cancel(item).flatMap(cancelled -> release(cancelled).then(Mono.error(rejection(cancelled))));
        if (result.getState()==TypeOperationState.TYPE_OPERATION_STATE_REJECTED) {
            DomainException error=new DomainException(DomainStatus.valueOf(result.getRejectionStatus()),
                    result.getRejectionMessage(),result.getRejectionDetailsMap());
            if (REJECTED.contains(error.getStatus())) return journal.cancelling(item,error).flatMap(this::drive);
        }
        return Mono.error(pending(item));
    }
    private Mono<Void> release(WorkflowBindingChange item) {
        if (item.released()) return Mono.empty();
        return issues.release(item.project(),item.id()).then(projects.release(item.project(),item.id()))
                .then(journal.released(item)).onErrorMap(error -> pending(item));
    }
    @Scheduled(fixedDelayString="${app.workflow-binding-changes.recovery-interval:5s}")
    public Mono<Void> recover() {
        return Mono.defer(() -> journal.recoverable().concatMap(item -> drive(item).onErrorResume(error -> {
            log.warn("Workflow binding recovery pending: operation={},status={}",item.id(),domain(error).getStatus());
            return journal.touched(item).then(Mono.empty());
        })).then());
    }
    private static DomainException rejection(WorkflowBindingChange item) {
        return new DomainException(item.errorStatus()==null?DomainStatus.ABORTED:DomainStatus.valueOf(item.errorStatus()),
                item.errorMessage()==null?"Workflow binding change was cancelled":item.errorMessage(),item.errorDetails());
    }
    private static DomainException pending(WorkflowBindingChange item) {
        return new DomainException(DomainStatus.UNAVAILABLE,"Workflow binding change is durably pending; retry with the same X-Request-Id",
                Map.of("reason","WORKFLOW_BINDING_CHANGE_PENDING","operationId",item.id().toString()));
    }
    public static DomainException domain(Throwable error) {
        if (error instanceof DomainException d) return d;
        if (error instanceof StatusRuntimeException grpc) return new DomainException(
                DomainStatus.valueOf(grpc.getStatus().getCode().name()),grpc.getStatus().getDescription()==null?
                    "Workflow operation unavailable":grpc.getStatus().getDescription(),mapper.GrpcErrorDetails.decode(grpc.getTrailers()));
        return new DomainException(DomainStatus.UNAVAILABLE,"Workflow operation unavailable");
    }
}
