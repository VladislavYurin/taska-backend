package ru.taska.transport.grpc.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsRequest;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsRequestBody;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.dto.UserSummary;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAuthServiceClient {
    private final ReactorProfileServiceGrpc.ReactorProfileServiceStub profileServiceStub;

    public Mono<GetUserDetailsByIdsResponse> getUserDetailsByIds(Collection<UUID> userIds, String requestId, String nodeId) {
        log.info("[{}][{}] Calling getUserDetailsByIds with: userId={}",
                requestId, nodeId, userIds);

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

    public Mono<Map<UUID, UserSummary>> getUserProfiles(
            Set<UUID> userIds, String requestId, String nodeId
    ) {
        return getUserDetailsByIds(userIds, requestId, nodeId)
                .map(response -> response.getUserDetailsList().stream()
                        .collect(Collectors.toMap(
                                this::extractUserId,
                                this::toUserSummary,
                                (existing, duplicate) -> existing
                        )));
    }

    private UUID extractUserId(UserDetails user) {
        return UUID.fromString(user.getUserId());
    }

    private UserSummary toUserSummary(UserDetails user) {
        return new UserSummary(
                extractUserId(user),
                user.getDisplayName(),
                user.hasAvatar() ? user.getAvatar().getDownloadUrl() : null
        );
    }
}
