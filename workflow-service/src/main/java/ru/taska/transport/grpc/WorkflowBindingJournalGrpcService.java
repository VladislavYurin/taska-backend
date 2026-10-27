package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.WorkflowBindingChangeRepository;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class WorkflowBindingJournalGrpcService extends ReactorWorkflowBindingJournalGrpc.WorkflowBindingJournalImplBase {
    private final WorkflowBindingChangeRepository journal;
    @Override public Mono<WorkflowBindingIntent> get(Mono<TypeOperationKey> request) {
        return request.flatMap(req -> Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getOperationId(),"operationId"))
                .flatMap(ids -> journal.get(ids.getT1(),ids.getT2()))
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Workflow binding operation not found")))
                .map(ru.taska.domain.WorkflowBindingChange::intent))
                .transform(GrpcExceptionHandler.withErrorHandling("workflowBindingJournalGet"));
    }
    @Override public Mono<WorkflowBindingIntent> getPending(Mono<TypeOperationProjectRequest> request) {
        return request.flatMap(req -> GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(),"projectId")
                .flatMap(journal::pending).map(ru.taska.domain.WorkflowBindingChange::intent)
                .defaultIfEmpty(WorkflowBindingIntent.getDefaultInstance()))
                .transform(GrpcExceptionHandler.withErrorHandling("workflowBindingJournalGetPending"));
    }
}
