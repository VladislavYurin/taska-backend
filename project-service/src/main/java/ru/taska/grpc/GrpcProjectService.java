package ru.taska.grpc;

import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.ProjectService;
import validator.GrpcRequestValidators;

import java.util.UUID;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class GrpcProjectService extends ReactorProjectServiceGrpc.ProjectServiceImplBase {
    private final ProjectService projectService;


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
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> !(e instanceof StatusRuntimeException),
                        e -> log.error("createProject failed", e))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    @Override
    public Mono<ProjectResponse> getProject(Mono<GetProjectRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId")))
                .flatMap( t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();

                        log.info("[{}][{}] Received request to getProject: projectId={}", requestId, nodeId, projectId);

                        return projectService.getProject(requestId, nodeId, projectId);
                })
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> !(e instanceof StatusRuntimeException),
                        e -> log.error("getProject failed", e))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
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
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> !(e instanceof StatusRuntimeException),
                        e -> log.error("listMyProjects failed", e))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }
}