package ru.taska.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.IssueTypeCreation;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueTypeCreationRepository;

@Service
@RequiredArgsConstructor
@Slf4j
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
public class IssueTypeCreationCoordinator {
    private final IssueTypeCreationTransactions transactions;
    private final IssueTypeCreationRepository journal;
    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    private static final Set<DomainStatus> REJECTED=Set.of(DomainStatus.INVALID_ARGUMENT,DomainStatus.FAILED_PRECONDITION,DomainStatus.ABORTED,
            DomainStatus.ALREADY_EXISTS,DomainStatus.PERMISSION_DENIED,DomainStatus.UNAUTHENTICATED,DomainStatus.NOT_FOUND);
    public Mono<IssueTypeCreationTransactions.Created> create(UUID project,UUID actor,String code,String name,String color,
            String icon,String shape,Integer sort,UUID workflow,Header header) {
        return Mono.deferContextual(context->transactions.begin(project,actor,header.getRequestId(),header.getNodeId(),code,name,color,icon,shape,sort,workflow,
                        ActionMetadata.current(context))).flatMap(this::drive);
    }
    public Mono<IssueTypeCreationTransactions.Created> drive(IssueTypeCreation item) {
        return switch(item.state()) {
            case "COMPLETED"->Mono.just(new IssueTypeCreationTransactions.Created(item.type(),item.resolvedWorkflowId()));
            case "CANCELLED"->Mono.error(rejection(item));
            case "CANCELLING"->stub().cancel(client.TypeOperationClients.key(item.projectId(),item.id()))
                    .onErrorMap(error->pending(item)).flatMap(result->finish(item,result));
            case "PENDING"->stub().apply(client.TypeOperationClients.key(item.projectId(),item.id()))
                    .onErrorMap(IssueTypeCreationCoordinator::domain).flatMap(result->finish(item,result))
                    .onErrorResume(DomainException.class,error->REJECTED.contains(error.getStatus())
                            ?transactions.requestCancellation(item,error).flatMap(this::drive):Mono.error(pending(item)));
            default->Mono.error(new IllegalStateException("Invalid creation state"));
        };
    }
    private Mono<IssueTypeCreationTransactions.Created> finish(IssueTypeCreation item,CreatedTypeBindingOutcome result) {
        var outcome=result.getOutcome();
        if(outcome.getState()==TypeOperationState.TYPE_OPERATION_STATE_APPLIED) {
            UUID workflow;
            try{workflow=UUID.fromString(result.getWorkflowId());}catch(IllegalArgumentException invalid){return Mono.error(pending(item));}
            return transactions.complete(item,workflow).onErrorMap(error->pending(item));
        }
        if(outcome.getState()==TypeOperationState.TYPE_OPERATION_STATE_CANCELLED)
            return journal.find(item.projectId(),item.id()).flatMap(current->transactions.cancel(current).onErrorMap(error->pending(item)).then(Mono.error(rejection(current))));
        if(outcome.getState()==TypeOperationState.TYPE_OPERATION_STATE_REJECTED) {
            var error=new DomainException(DomainStatus.valueOf(outcome.getRejectionStatus()),outcome.getRejectionMessage(),outcome.getRejectionDetailsMap());
            if(REJECTED.contains(error.getStatus()))return transactions.requestCancellation(item,error).flatMap(this::drive);
        }
        return Mono.error(pending(item));
    }
    private ReactorWorkflowCreatedTypeBindingGrpc.ReactorWorkflowCreatedTypeBindingStub stub() {
        return ReactorWorkflowCreatedTypeBindingGrpc.newReactorStub(workflowStub.getChannel()).withDeadlineAfter(20,TimeUnit.SECONDS);
    }
    @Scheduled(fixedDelayString="${app.issue-type-creations.recovery-interval:5s}")
    public Mono<Void> recover() {
        return Mono.defer(()->journal.recoverable().collectList().flatMapMany(reactor.core.publisher.Flux::fromIterable).concatMap(item->drive(item).then()
                .onErrorResume(error->{log.warn("Issue type creation recovery: operation={},status={}",item.id(),domain(error).getStatus());return journal.touched(item).onErrorResume(ignored->Mono.empty());})).then());
    }
    private static DomainException pending(IssueTypeCreation item) {return new DomainException(DomainStatus.UNAVAILABLE,
            "Issue type creation is pending; retry with the same X-Request-Id",Map.of("reason","ISSUE_TYPE_CREATION_PENDING","operationId",item.id().toString()));}
    private static DomainException rejection(IssueTypeCreation item) {return new DomainException(item.errorStatus()==null?DomainStatus.ABORTED:DomainStatus.valueOf(item.errorStatus()),
            item.errorMessage()==null?"Issue type creation cancelled":item.errorMessage(),item.errorDetails());}
    private static DomainException domain(Throwable error) {
        if(error instanceof DomainException domain)return domain;
        if(error instanceof io.grpc.StatusRuntimeException grpc)return new DomainException(DomainStatus.valueOf(grpc.getStatus().getCode().name()),
                grpc.getStatus().getDescription()==null?"Binding unavailable":grpc.getStatus().getDescription(),mapper.GrpcErrorDetails.decode(grpc.getTrailers()));
        return new DomainException(DomainStatus.UNAVAILABLE,"Binding unavailable");
    }
}
