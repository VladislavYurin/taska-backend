package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequestBody;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.Header;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAuthServiceClient {

    private final ReactorAuthServiceGrpc.ReactorAuthServiceStub authServiceStub;

    public Mono<ValidateAccessTokenResponse> validateAccessToken(String requestId, String nodeId, String accessToken) {

        log.info("[{}] Calling validateAccessToken", requestId);

        ValidateAccessTokenRequest request = ValidateAccessTokenRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(ValidateAccessTokenRequestBody.newBuilder()
                        .setAccessToken(accessToken)
                        .build())
                .build();

        return authServiceStub.validateAccessToken(request);
    }
}
