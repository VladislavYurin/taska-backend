package ru.taska.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.GetProjectRequestBody;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProjectServiceClient {

    private static final String NODE_ID = "issue-service";

    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    public Mono<String> getProjectKey(String requestId, UUID projectId) {
        GetProjectRequest request = GetProjectRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(NODE_ID)
                        .build())
                .setBody(GetProjectRequestBody.newBuilder()
                        .setProjectId(projectId.toString())
                        .build())
                .build();

        return projectServiceStub.getProject(Mono.just(request))
                .map(ProjectResponse::getProjectKey);
    }
}
