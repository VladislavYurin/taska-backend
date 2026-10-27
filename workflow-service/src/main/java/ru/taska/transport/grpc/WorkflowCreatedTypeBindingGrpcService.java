package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.service.WorkflowCreatedTypeBindingService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class WorkflowCreatedTypeBindingGrpcService extends ReactorWorkflowCreatedTypeBindingGrpc.WorkflowCreatedTypeBindingImplBase {
    private final WorkflowCreatedTypeBindingService service;
    @Override public Mono<CreatedTypeBindingOutcome> apply(Mono<TypeOperationKey> request) { return run(request,false); }
    @Override public Mono<CreatedTypeBindingOutcome> cancel(Mono<TypeOperationKey> request) { return run(request,true); }
    private Mono<CreatedTypeBindingOutcome> run(Mono<TypeOperationKey> request, boolean cancel) {
        return request.flatMap(req -> Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getOperationId(),"operationId"))
                .flatMap(ids -> cancel?service.cancel(ids.getT1(),ids.getT2()):service.apply(ids.getT1(),ids.getT2()))
                .onErrorResume(DomainException.class,error -> Mono.just(CreatedTypeBindingOutcome.newBuilder().setOutcome(
                        TypeOperationOutcome.newBuilder().setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED)
                                .setRejectionStatus(error.getStatus().name()).setRejectionMessage(error.getMessage())
                                .putAllRejectionDetails(error.getDetails())).build())))
                .transform(GrpcExceptionHandler.withErrorHandling("workflowCreatedTypeBinding"));
    }
}
