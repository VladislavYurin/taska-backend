package ru.taska.transport.grpc.project;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.GetProjectRequestBody;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcProjectServiceClient {

    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    public Mono<ProjectResponse> getProject(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId
    ) {
        log.info("[{}][{}] Calling getProject with: projectId={}, userId={}",
                 requestId, nodeId, projectId, userId);

        var request = GetProjectRequest.newBuilder()
                                       .setHeader(
                                               Header.newBuilder()
                                                     .setRequestId(requestId)
                                                     .setNodeId(nodeId)
                                                     .build()
                                       ).setBody(
                        GetProjectRequestBody.newBuilder()
                                             .setProjectId(projectId.toString())
                                             .setActorUserId(userId.toString())
                                             .build()
                ).build();

        return projectServiceStub.getProject(request)
                                 .doOnSuccess(project -> log.info("[{}][{}] getProject: found {} project",
                                                                  requestId, nodeId, project.getName()))
                                 .doOnError(e -> log.error("[{}][{}] getProject failed: {}",
                                                           requestId, nodeId, e.getMessage()));
    }
}
