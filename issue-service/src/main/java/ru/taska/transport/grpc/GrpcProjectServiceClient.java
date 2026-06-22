package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequestBody;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.GetProjectRequestBody;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

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

    public Mono<String> getProjectKey(String requestId, String nodeId, UUID projectId) {
        log.info("[{}][{}] Calling getProject with: projectId={}", requestId, nodeId, projectId);

        var request = GetProjectRequest.newBuilder()
                .setHeader(
                        Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build()
                )
                .setBody(
                        GetProjectRequestBody.newBuilder()
                                .setProjectId(projectId.toString())
                                .build()
                )
                .build();

        return projectServiceStub.getProject(request)
                .map(ProjectResponse::getProjectKey);
    }

}
