package ru.taska.transport.grpc;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ArchiveProjectRequest;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.mapper.ProjectMapper;
import ru.taska.service.ProjectArchiveService;
import validator.GrpcRequestValidators;

@Component
@RequiredArgsConstructor
public class ProjectArchiveGrpcService {
    private final ProjectArchiveService service;
    private final ProjectMapper mapper;

    public Mono<ProjectResponse> archive(Mono<ArchiveProjectRequest> request) {
        return request.flatMap(req -> Mono.zip(
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(), "projectId"),
                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(), "actorUserId")))
                .flatMap(c -> service.archive(c.getT3(), c.getT4(), c.getT1(), c.getT2()))
                .map(project -> mapper.toProjectResponse(project).toBuilder()
                        .setCurrentUserRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_ADMIN).build());
    }
}
