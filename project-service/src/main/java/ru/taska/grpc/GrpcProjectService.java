package ru.taska.grpc;

import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.ProjectService;
import validator.GrpcRequestValidators;

import java.util.UUID;
import java.util.function.Function;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class GrpcProjectService extends ReactorProjectServiceGrpc.ProjectServiceImplBase {
    private final ProjectService projectService;
    private final ProjectMemberService projectMemberService;

    @Override
    public Mono<ProjectResponse> createProject(Mono<CreateProjectRequest> request) {
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
                .transform(withErrorHandling("createProject"));
    }

    @Override
    public Mono<ProjectResponse> getProject(Mono<GetProjectRequest> request) {
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
                .transform(withErrorHandling("getProject"));
    }

    @Override
    public Mono<UsersProjectsResponse> listMyProjects(Mono<GetUsersProjectsRequest> request) {
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

                    return projectService.listMyProjects(requestId, nodeId, userId);
                })
                .transform(withErrorHandling("listMyProjects"));
    }

    @Override
    public Mono<AddProjectMemberResponse> addProjectMember(Mono<AddProjectMemberRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getAddedMemberId(), "body.addedMemberId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getAddingUserId(), "body.addingUserId"),
                        GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getRole(), "body.role"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID addedMemberId = t.getT3();
                    UUID addingUserId = t.getT4();
                    ProjectRole role = t.getT5();
                    UUID projectId = t.getT6();

                    log.info("[{}][{}] Received request to add member to project: addedMemberId = {}, addingUserId = {}, requestedRole = {}, projectId ={}",
                            requestId, nodeId, addedMemberId, addingUserId, role, projectId);

                    return projectMemberService.addProjectMember(requestId, nodeId, addedMemberId, addingUserId, role, projectId);
                })
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> e instanceof DuplicateKeyException,
                        _ -> new DomainException(DomainStatus.ALREADY_EXISTS, "Already exists"))
                .doOnError(e -> !(e instanceof StatusRuntimeException),
                        e -> log.error("addProjectMember failed " + e.getMessage()))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    @Override
    public Mono<RmProjectMemberResponse> rmProjectMember(Mono<RmProjectMemberRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getDeletedMemberId(), "body.deletedMemberId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID deletedMemberId = t.getT3();
                    UUID projectId = t.getT4();

                    log.info("[{}][{}] Received request to remove member from project: userId={}, projectId = {}",
                            requestId, nodeId, deletedMemberId, projectId);

                    return projectMemberService.rmProjectMember(requestId, nodeId, deletedMemberId, projectId);
                })
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> e instanceof DuplicateKeyException,
                        _ -> new DomainException(DomainStatus.ALREADY_EXISTS, "Already exists"))
                .doOnError(e -> !(e instanceof StatusRuntimeException),
                        e -> log.error("removeProjectMember failed " + e.getMessage()))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    @Override
    public Mono<ChangeRoleResponse> changeProjectMemberRole(Mono<ChangeRoleRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getChangedMemberId(), "body.changedMemberId"),
                        GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getRole(), "body,role"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))

                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID changedMemberId = t.getT3();
                    ProjectRole role = t.getT4();
                    UUID projectId = t.getT5();

                    log.info("[{}][{}] Received request to change user role in project: userId={}, role = {}, projectId = {}",
                            requestId, nodeId, changedMemberId, role, projectId);

                    return projectMemberService.changeProjectMemberRole(requestId, nodeId, changedMemberId, role, projectId);
                })
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> e instanceof DuplicateKeyException,
                        _ -> new DomainException(DomainStatus.ALREADY_EXISTS, "Already exists"))
                .doOnError(e -> !(e instanceof StatusRuntimeException),
                        e -> log.error("changeProjectMemberRole failed " + e.getMessage()))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    @Override
    public Mono<CheckProjectMemberRoleResponse> checkProjectMemberRole(Mono<CheckProjectMemberRoleRequest> request) {
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
                .transform(withErrorHandling("checkProjectRole"));

    }

    //TODO: Создать общий GrpcErrorHanlder
    private <T> Function<Mono<T>, Mono<T>> withErrorHandling(String operationName) {
        return mono -> mono
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        e -> {
                            log.error("{}: database error", operationName, e);
                            return new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable");
                        })
                .onErrorMap(e -> !(e instanceof DomainException) && !(e instanceof StatusRuntimeException),
                        e -> {
                            log.error("{}: unexpected error", operationName, e);
                            return new DomainException(DomainStatus.INTERNAL, "Internal error");
                        })
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }
}