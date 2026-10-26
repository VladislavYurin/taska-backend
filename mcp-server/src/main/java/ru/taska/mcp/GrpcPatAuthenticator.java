package ru.taska.mcp;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequestBody;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.Header;

@Configuration
public class GrpcPatAuthenticator {
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel authChannel(McpProperties properties) {
    return ManagedChannelBuilder.forAddress(properties.auth().host(), properties.auth().port())
        .usePlaintext()
        .build();
  }

  @Bean
  PatAuthenticator patAuthenticator(ManagedChannel authChannel, McpProperties properties) {
    var stub = ReactorAuthServiceGrpc.newReactorStub(authChannel);
    // Deliberately no positive cache: revocation and status changes apply on the next request.
    return (token, requestId) ->
        stub.withDeadlineAfter(properties.auth().deadline().toMillis(), TimeUnit.MILLISECONDS)
            .validateAccessToken(
                ValidateAccessTokenRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId("mcp-server"))
                    .setBody(
                        ValidateAccessTokenRequestBody.newBuilder()
                            .setAccessToken(token)
                            .setAllowPersonalToken(true))
                    .build())
            .map(ValidateAccessTokenResponse::getUserContext);
  }
}
