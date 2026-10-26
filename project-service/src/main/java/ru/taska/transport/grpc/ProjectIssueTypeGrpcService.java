package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import java.util.function.BiFunction;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.ListProjectIssueTypesResponse;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ProjectIssueTypesRequest;
import ru.taska.mapper.ProjectMapper;
import ru.taska.service.ProjectIssueTypeService;
import validator.GrpcRequestValidators;

@Service
@RequiredArgsConstructor
public class ProjectIssueTypeGrpcService {
    private final ProjectIssueTypeService service;
    private final ProjectMapper mapper;

    public Mono<ListProjectIssueTypesResponse> list(Mono<ProjectIssueTypesRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.list(project, actor, req.getHeader().getRequestId(), req.getHeader().getNodeId())
                        .map(mapper::toIssueTypeResponse).collectList()
                        .map(items -> ListProjectIssueTypesResponse.newBuilder().addAllItems(items).build())));
    }

    public Mono<ProjectIssueTypeResponse> create(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.create(project, actor, req.getCode(), req.hasName() ? req.getName() : null,
                        req.hasColor() ? req.getColor() : null, req.hasIcon() ? req.getIcon() : null,
                        req.hasSortOrder() ? req.getSortOrder() : null).map(mapper::toIssueTypeResponse)));
    }

    public Mono<ProjectIssueTypeResponse> update(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.update(project, actor, req.getCode(), req.hasName() ? req.getName() : null,
                        req.hasColor() ? req.getColor() : null, req.hasIcon() ? req.getIcon() : null,
                        req.hasSortOrder() ? req.getSortOrder() : null).map(mapper::toIssueTypeResponse)));
    }

    public Mono<ListProjectIssueTypesResponse> reorder(Mono<ru.taska.api.project.v1.ReorderProjectIssueTypesRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.reorder(project, actor, req.getCodesList()).map(items -> ListProjectIssueTypesResponse.newBuilder()
                        .addAllItems(items.stream().map(mapper::toIssueTypeResponse).toList()).build())));
    }

    public Mono<Empty> delete(Mono<ChangeProjectIssueTypeRequest> request) {
        return request.flatMap(req -> ids(req.getProjectId(), req.getActorUserId(), (project, actor) ->
                service.delete(project, actor, req.getCode(), req.getHeader().getRequestId(),
                        req.getHeader().getNodeId()).thenReturn(Empty.getDefaultInstance())));
    }

    private <T> Mono<T> ids(String project, String actor, BiFunction<UUID, UUID, Mono<T>> operation) {
        return Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(project, "projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(actor, "actorUserId"))
                .flatMap(ids -> operation.apply(ids.getT1(), ids.getT2()));
    }
}
