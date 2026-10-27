package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.domain.IssueTypeCreation;
import ru.taska.repository.IssueTypeCreationRepository;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class ProjectIssueTypeCreationJournalGrpcService extends ReactorProjectIssueTypeCreationJournalGrpc.ProjectIssueTypeCreationJournalImplBase {
    private final IssueTypeCreationRepository journal;
    @Override public Mono<IssueTypeCreationIntent> get(Mono<TypeOperationKey> request) {
        return request.flatMap(key->Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(key.getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(key.getOperationId(),"operationId")))
                .flatMap(ids->journal.find(ids.getT1(),ids.getT2())).switchIfEmpty(Mono.error(new ru.taska.exception.DomainException(
                        ru.taska.exception.DomainStatus.NOT_FOUND,"Creation intent not found")))
                .map(IssueTypeCreation::toProto).transform(exception.GrpcExceptionHandler.withErrorHandling("getIssueTypeCreationIntent"));
    }
}
