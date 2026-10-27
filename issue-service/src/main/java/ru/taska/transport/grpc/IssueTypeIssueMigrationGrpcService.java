package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.service.IssueTypeMoveService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class IssueTypeIssueMigrationGrpcService extends ReactorIssueTypeIssueMigrationGrpc.IssueTypeIssueMigrationImplBase {
  private final IssueTypeMoveService service;
  @Override public Mono<IssueTypeMoveOutcome> apply(Mono<IssueTypeDeletionKey> request) {
    return request.flatMap(key->ids(key).flatMap(ids->service.apply(ids.getT1(),ids.getT2())))
        .onErrorResume(DomainException.class,error->Mono.just(IssueTypeMoveOutcome.newBuilder()
            .setState(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_REJECTED).setRejectionStatus(error.getStatus().name())
            .setRejectionMessage(error.getMessage()).putAllRejectionDetails(error.getDetails()).build()))
        .transform(GrpcExceptionHandler.withErrorHandling("applyIssueTypeDeletion"));
  }
  @Override public Mono<IssueTypeMoveOutcome> cancel(Mono<IssueTypeDeletionKey> request) {
    return request.flatMap(key->ids(key).flatMap(ids->service.cancel(ids.getT1(),ids.getT2())))
        .transform(GrpcExceptionHandler.withErrorHandling("cancelIssueTypeDeletion"));
  }
  private Mono<reactor.util.function.Tuple2<UUID,UUID>> ids(IssueTypeDeletionKey key){return Mono.zip(
      GrpcRequestValidators.parseUuidOrInvalidArgument(key.getProjectId(),"projectId"),
      GrpcRequestValidators.parseUuidOrInvalidArgument(key.getOperationId(),"operationId"));}
}
