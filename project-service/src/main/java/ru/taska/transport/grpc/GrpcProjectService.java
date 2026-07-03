package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.AddProjectMemberRequest;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleRequest;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.CreateProjectRequest;
import ru.taska.api.project.v1.CreateProjectResponse;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.GetProjectResponse;
import ru.taska.api.project.v1.ListMyProjectsRequest;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.project.v1.RmProjectMemberRequest;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.mapper.ProjectMapper;
import ru.taska.mapper.ProjectMemberMapper;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.ProjectService;
import validator.GrpcRequestValidators;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class GrpcProjectService extends ReactorProjectServiceGrpc.ProjectServiceImplBase {
    private final ProjectService projectService;
    private final ProjectMemberService projectMemberService;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    @Override
    public Mono<CreateProjectResponse> createProject(Mono<CreateProjectRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getName(), "body.name"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getProjectKey(), "body.projectKey"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getUserId(), "body.userId")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    String projectName = t.getT3();
                    String projectKey = t.getT4();
                    UUID userId = t.getT5();

                    log.info("[{}][{}] Received request to createProject: projectKey={}, projectName={}, userId={}", requestId, nodeId, projectKey, projectName, userId);

                    return projectService.createProject(requestId, nodeId, projectKey, projectName, userId);
                })
                .map(p -> CreateProjectResponse.newBuilder().setProject(projectMapper.toProjectResponse(p)).build())
                .transform(GrpcExceptionHandler.withErrorHandling("createProject"));
    }

    @Override
    public Mono<GetProjectResponse> getProject(Mono<GetProjectRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();

                    log.info("[{}][{}] Received request to getProject: projectId={}", requestId, nodeId, projectId);

                    return projectService.getProject(requestId, nodeId, projectId);
                })
                .map(p -> GetProjectResponse.newBuilder().setProject(projectMapper.toProjectResponse(p)).build())
                .transform(GrpcExceptionHandler.withErrorHandling("getProject"));
    }

    @Override
    public Mono<ListMyProjectsResponse> listMyProjects(Mono<ListMyProjectsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getUserId(), "body.userId")))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID userId = t.getT3();

                    log.info("[{}][{}] Received request to get listMyProjects: userId={}", requestId, nodeId, userId);

                    return projectService.listMyProjects(requestId, nodeId, userId)
                            .map(projectMapper::toProjectResponse)
                            .collectList()
                            .map(p -> ListMyProjectsResponse.newBuilder()
                                    .addAllProjects(p)
                                    .build());
                })
                .transform(GrpcExceptionHandler.withErrorHandling("listMyProjects"));
    }

    @Override
    public Mono<AddProjectMemberResponse> addProjectMember(Mono<AddProjectMemberRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getAddedMemberId(), "body.addedMemberId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId()"),
                        GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getRole(), "body.role"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID addedMemberId = t.getT3();
                    UUID actorUserId = t.getT4();
                    ProjectRole role = projectMemberMapper.toProjectRole(t.getT5());
                    UUID projectId = t.getT6();

                    log.info("[{}][{}] Received request to add member to project: addedMemberId = {}, addingUserId = {}, requestedRole = {}, projectId ={}",
                            requestId, nodeId, addedMemberId, actorUserId, role, projectId);

                    return projectMemberService.addProjectMember(requestId, nodeId, addedMemberId, actorUserId, role, projectId);
                })
                .map(projectMemberMapper::toAddProjectMemberResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("addProjectMember"));
    }

    @Override
    public Mono<RmProjectMemberResponse> rmProjectMember(Mono<RmProjectMemberRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getDeletedMemberId(), "body.deletedMemberId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId()"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID deletedMemberId = t.getT3();
                    UUID actorUserId = t.getT4();
                    UUID projectId = t.getT5();

                    log.info("[{}][{}] Received request to remove member from project: userId={}, projectId = {}, actorId = {}",
                            requestId, nodeId, deletedMemberId, projectId, actorUserId);

                    return projectMemberService.rmProjectMember(requestId, nodeId, deletedMemberId, actorUserId, projectId);
                })
                .map(projectMemberMapper::toRmProjectMemberResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("rmProjectMember"));
    }

    @Override
    public Mono<ChangeProjectMemberRoleResponse> changeProjectMemberRole(Mono<ChangeProjectMemberRoleRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getChangedMemberId(), "body.changedMemberId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId()"),
                        GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getRole(), "body.role"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))

                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID changedMemberId = t.getT3();
                    UUID actorUserId = t.getT4();
                    ProjectRole role = projectMemberMapper.toProjectRole(t.getT5());
                    UUID projectId = t.getT6();

                    log.info("[{}][{}] Received request to change user role in project: userId={}, actorId = {}, role = {}, projectId = {}",
                            requestId, nodeId, changedMemberId, actorUserId, role, projectId);

                    return projectMemberService.changeProjectMemberRole(requestId, nodeId, changedMemberId, actorUserId, role, projectId);
                })
                .map(projectMemberMapper::toChangeProjectMemberRoleResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("changeProjectMemberRole"));
    }

    @Override
    public Mono<CheckProjectMemberRoleResponse> checkProjectMemberRole
            (Mono<CheckProjectMemberRoleRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getProjectId(), "body.projectId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getUserId(), "body.userId"
                                )
                        )
                )
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();
                    UUID userId = t.getT4();

                    log.info("[{}][{}] Received checkProjectRole request: projectId={}, userId={}",
                            requestId, nodeId, projectId, userId);

                    return projectMemberService.checkProjectMemberRole(requestId, nodeId, projectId, userId);
                })
                .map(projectMemberMapper::toCheckProjectRoleResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("checkProjectRole"));
    }
}