package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.domain.IssueTypeDeletion;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueTypeDeletionRepository;
import validator.GrpcRequestValidators;

/** Internal recovery journal, deliberately independent of the original actor's current role. */
@GrpcService
@RequiredArgsConstructor
public class IssueTypeDeletionJournalGrpcService extends ReactorIssueTypeDeletionJournalGrpc.IssueTypeDeletionJournalImplBase {
  private final IssueTypeDeletionRepository journal;
  @Override public Mono<IssueTypeDeletionIntent> getIntent(Mono<IssueTypeDeletionKey> request) {
    return request.flatMap(key->Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(key.getProjectId(),"projectId"),
            GrpcRequestValidators.parseUuidOrInvalidArgument(key.getOperationId(),"operationId")))
        .flatMap(ids->journal.find(ids.getT1(),ids.getT2()))
        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Deletion intent not found")))
        .map(IssueTypeDeletion::toProto).transform(GrpcExceptionHandler.withErrorHandling("getIssueTypeDeletionIntent"));
  }
}
