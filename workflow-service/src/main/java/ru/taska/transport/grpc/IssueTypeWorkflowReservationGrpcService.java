package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import exception.GrpcExceptionHandler;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.IssueTypeDeletionKey;
import ru.taska.api.registry.v1.IssueTypeMoveWorkflowSnapshot;
import ru.taska.api.registry.v1.ReactorIssueTypeWorkflowReservationGrpc;
import ru.taska.service.IssueTypeWorkflowReservationService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class IssueTypeWorkflowReservationGrpcService extends ReactorIssueTypeWorkflowReservationGrpc.IssueTypeWorkflowReservationImplBase {
    private final IssueTypeWorkflowReservationService service;

    @Override public Mono<IssueTypeMoveWorkflowSnapshot> reserve(Mono<IssueTypeDeletionKey> request) {
        return request.flatMap(key -> ids(key).flatMap(ids -> service.reserve(ids.project(), ids.operation())))
                .transform(GrpcExceptionHandler.withErrorHandling("reserveIssueTypeWorkflow"));
    }
    @Override public Mono<IssueTypeMoveWorkflowSnapshot> get(Mono<IssueTypeDeletionKey> request) {
        return request.flatMap(key -> ids(key).flatMap(ids -> service.get(ids.project(), ids.operation())))
                .transform(GrpcExceptionHandler.withErrorHandling("getIssueTypeWorkflowReservation"));
    }
    @Override public Mono<Empty> release(Mono<IssueTypeDeletionKey> request) {
        return request.flatMap(key -> ids(key).flatMap(ids -> service.release(ids.project(), ids.operation())))
                .thenReturn(Empty.getDefaultInstance())
                .transform(GrpcExceptionHandler.withErrorHandling("releaseIssueTypeWorkflow"));
    }
    private Mono<Ids> ids(IssueTypeDeletionKey key) {
        return Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(key.getProjectId(), "projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(key.getOperationId(), "operationId"))
                .map(ids -> new Ids(ids.getT1(), ids.getT2()));
    }
    private record Ids(UUID project, UUID operation) { }
}
