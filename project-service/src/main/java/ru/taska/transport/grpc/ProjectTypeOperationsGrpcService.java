package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.repository.ProjectTypeOperationRepository;
import ru.taska.service.WorkflowTypeReservationTransactions;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class ProjectTypeOperationsGrpcService extends ReactorProjectTypeOperationsGrpc.ProjectTypeOperationsImplBase {
    private final ProjectTypeOperationRepository operations;
    private final WorkflowTypeReservationTransactions transactions;
    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    @Override public Mono<ProjectTypeOperationGate> getGate(Mono<TypeOperationProjectRequest> request) {
        return request.flatMap(req->GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(),"projectId"))
                .flatMap(operations::active).defaultIfEmpty(ProjectTypeOperationGate.getDefaultInstance())
                .transform(exception.GrpcExceptionHandler.withErrorHandling("projectTypeOperationGate"));
    }
    @Override public Mono<Empty> reserveWorkflowBindings(Mono<TypeOperationKey> request) { return run(request,true); }
    @Override public Mono<Empty> releaseWorkflowBindings(Mono<TypeOperationKey> request) { return run(request,false); }
    private Mono<Empty> run(Mono<TypeOperationKey> request,boolean reserve) {
        return request.flatMap(req->Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getOperationId(),"operationId"))
                .flatMap(ids->ReactorWorkflowBindingJournalGrpc.newReactorStub(workflowStub.getChannel()).withDeadlineAfter(5,TimeUnit.SECONDS)
                        .get(client.TypeOperationClients.key(ids.getT1(),ids.getT2())).flatMap(intent->{
                            if(!intent.getProjectId().equals(ids.getT1().toString())||!intent.getOperationId().equals(ids.getT2().toString()))
                                return Mono.error(new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.UNAVAILABLE,"Workflow journal identity mismatch"));
                            return reserve?transactions.reserve(intent):transactions.release(intent);
                        }))).thenReturn(Empty.getDefaultInstance())
                .transform(exception.GrpcExceptionHandler.withErrorHandling("projectWorkflowTypeReservation"));
    }
}
