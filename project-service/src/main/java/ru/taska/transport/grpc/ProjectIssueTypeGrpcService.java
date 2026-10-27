package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import java.util.function.BiFunction;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import nullable.NullableField;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.ListProjectIssueTypesResponse;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ProjectIssueTypesRequest;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMapper;
import ru.taska.service.ProjectIssueTypeService;
import validator.GrpcRequestValidators;

@Service
@RequiredArgsConstructor
public class ProjectIssueTypeGrpcService {
    private final ProjectIssueTypeService service;
    private final ProjectMapper mapper;
    private final ru.taska.service.IssueTypeDeletionCoordinator deletion;
    private final ru.taska.service.IssueTypeCreationCoordinator creation;

    public Mono<ListProjectIssueTypesResponse> list(Mono<ProjectIssueTypesRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.list(project, actor, req.getHeader().getRequestId(), req.getHeader().getNodeId())
                        .map(mapper::toIssueTypeResponse).collectList()
                        .map(items -> ListProjectIssueTypesResponse.newBuilder().addAllItems(items).build())));
    }

    public Mono<ProjectIssueTypeResponse> create(Mono<ChangeProjectIssueTypeRequest> request) {
        return createOperation(request).map(created->mapper.toIssueTypeResponse(created.type()));
    }
    public Mono<ru.taska.api.project.v1.CreateProjectIssueTypeWithWorkflowResult> createWithWorkflow(Mono<ChangeProjectIssueTypeRequest> request) {
        return createOperation(request).map(created->ru.taska.api.project.v1.CreateProjectIssueTypeWithWorkflowResult.newBuilder()
                        .setIssueType(mapper.toIssueTypeResponse(created.type())).setWorkflowId(created.workflowId().toString()).build())
                .onErrorResume(DomainException.class,error->Mono.just(ru.taska.api.project.v1.CreateProjectIssueTypeWithWorkflowResult.newBuilder()
                        .setRejectionStatus(error.getStatus().name()).setRejectionMessage(error.getMessage()).putAllRejectionDetails(error.getDetails()).build()));
    }
    private Mono<ru.taska.service.IssueTypeCreationTransactions.Created> createOperation(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req->ids(req.getProjectId(),req.getActorUserId(),(project,actor)->{
            Mono<java.util.Optional<UUID>> workflow=req.hasWorkflowId()?GrpcRequestValidators.parseUuidOrInvalidArgument(req.getWorkflowId(),"workflowId").map(java.util.Optional::of):Mono.just(java.util.Optional.empty());
            return workflow.flatMap(id->creation.create(project,actor,req.getCode(),req.hasName()?req.getName():null,
                    req.hasColor()?req.getColor():null,req.hasIcon()?req.getIcon():null,req.hasShape()?req.getShape():null,
                    req.hasSortOrder()?req.getSortOrder():null,id.orElse(null),req.getHeader()))
                    .contextWrite(context->action.GrpcActionContext.install(context,req.getHeader()));
        }));
    }

    public Mono<ProjectIssueTypeResponse> update(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                iconChange(req).flatMap(icon -> service.update(project, actor, req.getCode(),
                        req.hasName() ? req.getName() : null, req.hasColor() ? req.getColor() : null, icon,
                        req.hasShape() ? req.getShape() : null,
                        req.hasSortOrder() ? req.getSortOrder() : null)).map(mapper::toIssueTypeResponse)));
    }

    /** clear_icon carries an explicit REST null across gRPC, where an unset optional string means "absent". */
    private static Mono<NullableField<String>> iconChange(ChangeProjectIssueTypeRequest req) {
        if (req.getClearIcon() && req.hasIcon()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "icon and clear_icon are mutually exclusive"));
        }
        if (req.getClearIcon()) return Mono.just(NullableField.of(null));
        return Mono.just(req.hasIcon() ? NullableField.of(req.getIcon()) : NullableField.absent());
    }

    public Mono<ListProjectIssueTypesResponse> reorder(Mono<ru.taska.api.project.v1.ReorderProjectIssueTypesRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.reorder(project, actor, req.getCodesList()).map(items -> ListProjectIssueTypesResponse.newBuilder()
                        .addAllItems(items.stream().map(mapper::toIssueTypeResponse).toList()).build())));
    }

    public Mono<Empty> delete(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req -> req.hasMoveIssuesTo()
                ? Mono.error(new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.INVALID_ARGUMENT,
                        "Move requests require DeleteProjectIssueTypeWithMigration"))
                : executeDelete(Mono.just(req)));
    }

    private Mono<Empty> executeDelete(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) -> {
            Mono<java.util.Optional<UUID>> expected=req.hasExpectedGeneration()
                    ? GrpcRequestValidators.parseUuidOrInvalidArgument(req.getExpectedGeneration(),"expectedGeneration").map(java.util.Optional::of)
                    : Mono.just(java.util.Optional.empty());
            return expected.flatMap(generation->deletion.delete(project,actor,req.getCode(),
                    req.hasMoveIssuesTo()?req.getMoveIssuesTo():null,req.getStatusMappingsMap(),generation.orElse(null),req.getHeader()))
                    .contextWrite(context->action.GrpcActionContext.install(context,req.getHeader()))
                    .thenReturn(Empty.getDefaultInstance());
        }));
    }

    public Mono<ru.taska.api.project.v1.DeleteProjectIssueTypeResult> deleteWithMigration(Mono<ChangeProjectIssueTypeRequest> request) {
        return executeDelete(request)
                .map(ignored -> ru.taska.api.project.v1.DeleteProjectIssueTypeResult.newBuilder().setCompleted(true).build())
                .onErrorResume(ru.taska.exception.DomainException.class, error -> Mono.just(
                        ru.taska.api.project.v1.DeleteProjectIssueTypeResult.newBuilder()
                                .setRejectionStatus(error.getStatus().name())
                                .setRejectionMessage(error.getMessage())
                                .putAllRejectionDetails(error.getDetails()).build()));
    }

    private <T> Mono<T> ids(String project, String actor, BiFunction<UUID, UUID, Mono<T>> operation) {
        return Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(project, "projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(actor, "actorUserId"))
                .flatMap(ids -> operation.apply(ids.getT1(), ids.getT2()));
    }
}
