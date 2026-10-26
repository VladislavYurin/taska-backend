package ru.taska.transport.grpc.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequestBody;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcProjectServiceClient {

    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    public Mono<java.util.List<ru.taska.domain.IssueType>> listIssueTypes(
            String requestId, String nodeId, UUID projectId, UUID actorUserId) {
        return client.ProjectIssueTypes.list(projectServiceStub, requestId, nodeId, projectId, actorUserId);
    }

    public Mono<java.util.List<ru.taska.api.project.v1.ProjectIssueTypeResponse>> listIssueTypeDetails(
            String requestId, String nodeId, UUID projectId, UUID actorUserId) {
        return projectServiceStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .listProjectIssueTypes(ru.taska.api.project.v1.ProjectIssueTypesRequest.newBuilder()
                        .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId))
                        .setProjectId(projectId.toString()).setActorUserId(actorUserId.toString()).build())
                .map(ru.taska.api.project.v1.ListProjectIssueTypesResponse::getItemsList);
    }

    public Mono<java.util.List<ru.taska.api.project.v1.ProjectIssueTypeResponse>> getIssueTypesInternal(UUID projectId) {
        return projectServiceStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .getProjectInfoBatchInternal(ru.taska.api.project.v1.GetProjectInfoBatchInternalRequest.newBuilder()
                        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("workflow-service"))
                        .setBody(ru.taska.api.project.v1.GetProjectInfoBatchInternalRequestBody.newBuilder()
                                .addProjectIds(projectId.toString())).build())
                .map(response -> response.getProjectsMap().getOrDefault(projectId.toString(),
                        ru.taska.api.project.v1.ProjectShortInfo.getDefaultInstance()).getIssueTypesList());
    }

    public Mono<Void> requireIssueType(String requestId, String nodeId, UUID projectId, UUID actor,
                                      ru.taska.domain.IssueType type) {
        return listIssueTypes(requestId, nodeId, projectId, actor)
                .flatMap(allowed -> client.ProjectIssueTypes.require(allowed, type));
    }

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
}
