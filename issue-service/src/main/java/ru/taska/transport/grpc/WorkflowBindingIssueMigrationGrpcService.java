package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.service.WorkflowBindingMigrationService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class WorkflowBindingIssueMigrationGrpcService extends ReactorWorkflowBindingIssueMigrationGrpc.WorkflowBindingIssueMigrationImplBase {
    private final WorkflowBindingMigrationService service;
    @Override public Mono<TypeOperationOutcome> apply(Mono<TypeOperationKey> request) {return run(request,0);}
    @Override public Mono<TypeOperationOutcome> cancel(Mono<TypeOperationKey> request) {return run(request,1);}
    @Override public Mono<TypeOperationOutcome> release(Mono<TypeOperationKey> request) {return run(request,2);}
    private Mono<TypeOperationOutcome> run(Mono<TypeOperationKey> request,int mode) {
        return request.flatMap(key->Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(key.getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(key.getOperationId(),"operationId")))
                .flatMap(ids->mode==0?service.apply(ids.getT1(),ids.getT2()):mode==1?service.cancel(ids.getT1(),ids.getT2()):service.release(ids.getT1(),ids.getT2()))
                .onErrorResume(DomainException.class,error->Mono.just(TypeOperationOutcome.newBuilder().setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED)
                        .setRejectionStatus(error.getStatus().name()).setRejectionMessage(error.getMessage()).putAllRejectionDetails(error.getDetails()).build()))
                .transform(exception.GrpcExceptionHandler.withErrorHandling("workflowBindingIssueMigration"));
    }
}
