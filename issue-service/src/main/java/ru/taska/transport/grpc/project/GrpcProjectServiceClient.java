package ru.taska.transport.grpc.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequestBody;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.GetProjectKeyInternalRequest;
import ru.taska.api.project.v1.GetProjectKeyInternalRequestBody;
import ru.taska.api.project.v1.ProjectKeyResponse;
import ru.taska.api.project.v1.ListMyProjectsRequest;
import ru.taska.api.project.v1.ListMyProjectsRequestBody;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcProjectServiceClient {

    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    public Mono<CheckProjectMemberRoleResponse> checkProjectRole(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId
    ) {
        log.info("[{}][{}] Calling checkProjectRole with: projectId={}, userId={}",
                requestId, nodeId, projectId, userId);

        var request = CheckProjectMemberRoleRequest.newBuilder()
                .setHeader(
                        Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build()
                )
                .setBody(
                        CheckProjectMemberRoleRequestBody.newBuilder()
                                .setProjectId(projectId.toString())
                                .setUserId(userId.toString())
                                .build()
                )
                .build();

        return projectServiceStub.checkProjectMemberRole(request);
    }

    public Mono<String> getProjectKeyInternal(String requestId, String nodeId, UUID projectId) {
        GetProjectKeyInternalRequest request = GetProjectKeyInternalRequest.newBuilder()
                .setHeader(
                        Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build()
                )
                .setBody(
                        GetProjectKeyInternalRequestBody.newBuilder()
                                .setProjectId(projectId.toString())
                                .build()
                )
                .build();

        log.info("[{}][{}] Calling getProjectKeyInternal for projectId: {}",
                requestId, nodeId, projectId);

        return projectServiceStub.getProjectKeyInternal(request)
                .map(ProjectKeyResponse::getProjectKey);
    }

    public Mono<List<ProjectResponse>> listMyProjects(String requestId, String nodeId, UUID userId) {
        log.info("[{}][{}] Calling listMyProjects with: userId={}", requestId, nodeId, userId);

        var request = ListMyProjectsRequest.newBuilder()
                .setHeader(
                        Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build()
                )
                .setBody(
                        ListMyProjectsRequestBody.newBuilder()
                                .setUserId(userId.toString())
                                .build()
                )
                .build();

        return projectServiceStub.listMyProjects(request)
                .map(ListMyProjectsResponse::getProjectResponseList)
                .doOnSuccess(projects -> log.info("[{}][{}] listMyProjects: found {} projects",
                        requestId, nodeId, projects.size()))
                .doOnError(e -> log.error("[{}][{}] listMyProjects failed: {}",
                        requestId, nodeId, e.getMessage()));
    }
}
