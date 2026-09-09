package ru.taska.transport.grpc.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsRequest;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsRequestBody;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;
import ru.taska.api.common.v1.Header;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAuthServiceClient {
    private final ReactorProfileServiceGrpc.ReactorProfileServiceStub profileServiceStub;

    public Mono<GetUserDetailsByIdsResponse> getUserDetailsByIds(List<UUID> userIds, String requestId, String nodeId) {
        var request = GetUserDetailsByIdsRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId).build())
                .setBody(
                        GetUserDetailsByIdsRequestBody.newBuilder()
                                .addAllUserIds(userIds.stream().map(UUID::toString).toList())
                                .build()
                )
                .build();

        return profileServiceStub.getUserDetailsByIds(request);
    }
}
