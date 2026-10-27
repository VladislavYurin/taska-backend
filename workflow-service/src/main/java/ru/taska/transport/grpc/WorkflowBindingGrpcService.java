package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.*;
import ru.taska.mapper.WorkflowCreationMapper;
import ru.taska.service.WorkflowBindingCoordinator;
import ru.taska.service.WorkflowMutationService;
import ru.taska.exception.DomainException;
import validator.GrpcRequestValidators;

/** Typed body keeps complete migration rejections out of gRPC's small metadata/trailer limit. */
@Service
@RequiredArgsConstructor
public class WorkflowBindingGrpcService {
    private final WorkflowBindingCoordinator bindings;
    private final WorkflowCreationMapper creations;
    private final WorkflowMutationGrpcService mutations;

    public Mono<WorkflowBindingOperationResult> create(Mono<CreateWorkflowRequest> request) {
        return request.flatMap(req -> Mono.zip(
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(),"actorUserId"))
                .flatMap(ids -> {
                    Mono<Void> keys=req.getBody().getIssueTypeKeysCount()>0
                            ? reactor.core.publisher.Flux.fromIterable(req.getBody().getIssueTypeKeysList())
                                .concatMap(key -> validator.IssueTypeKeys.required(true,key,IssueType.ISSUE_TYPE_UNSPECIFIED)).then()
                            : reactor.core.publisher.Flux.fromIterable(req.getBody().getIssueTypesList())
                                .concatMap(key -> validator.IssueTypeKeys.required(false,"",key)).then();
                    return keys.then(Mono.defer(() -> bindings.create(req.getHeader(),ids.getT2(),creations.toDomainDto(ids.getT1(),req.getBody()))));
                })
                .contextWrite(context -> action.GrpcActionContext.install(context,req.getHeader())))
                .map(result -> WorkflowBindingOperationResult.newBuilder().setResult(result).build())
                .onErrorResume(error -> rejection(error));
    }
    public Mono<WorkflowBindingOperationResult> update(Mono<WorkflowMutationRequest> request) {
        return mutations.mutate(WorkflowMutationService.Operation.UPDATE_WORKFLOW,request)
                .map(result -> WorkflowBindingOperationResult.newBuilder().setResult(result).build())
                .onErrorResume(error -> rejection(error));
    }
    private Mono<WorkflowBindingOperationResult> rejection(Throwable error) {
        if (!(error instanceof DomainException) && !(error instanceof io.grpc.StatusRuntimeException)) return Mono.error(error);
        DomainException domain=WorkflowBindingCoordinator.domain(error);
        return Mono.just(WorkflowBindingOperationResult.newBuilder().setRejectionStatus(domain.getStatus().name())
                .setRejectionMessage(domain.getMessage()).putAllRejectionDetails(domain.getDetails()).build());
    }
}
