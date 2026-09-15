package ru.taska.transport.grpc.project;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.CheckProjectAccessRequest;
import ru.taska.api.project.v1.CheckProjectAccessRequestBody;
import ru.taska.api.project.v1.CheckProjectAccessResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcProjectServiceClient {

    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    public Mono<CheckProjectAccessResponse> checkProjectAccess(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId
    ) {
        log.info("[{}][{}] Calling checkProjectAccess with: projectId={}, userId={}",
                requestId, nodeId, projectId, userId);

        var request = CheckProjectAccessRequest.newBuilder()
                                               .setHeader(
                        Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build()
                )
                                               .setBody(
                                                       CheckProjectAccessRequestBody.newBuilder()
                                                                                    .setProjectId(projectId.toString())
                                                                                    .setUserId(userId.toString())
                                                                                    .build()
                )
                                               .build();

        return projectServiceStub.checkProjectAccess(request);
    }
}
