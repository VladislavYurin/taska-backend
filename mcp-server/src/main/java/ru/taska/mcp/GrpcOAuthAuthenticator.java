package ru.taska.mcp;

import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.oauth.v1.*;
import ru.taska.api.common.v1.Header;
import security.OAuthServiceCredentials;

@Configuration
class GrpcOAuthAuthenticator {
  @Bean
  McpOAuthAuthenticator mcpOAuthAuthenticator(
      ManagedChannel authChannel, McpProperties properties, McpOAuthProperties oauth) {
    var stub =
        ReactorOAuthIdentityServiceGrpc.newReactorStub(authChannel)
            .withInterceptors(OAuthServiceCredentials.interceptor(oauth.exchangeCredential()));
    return (token, requestId) -> {
      if (!oauth.enabled()) return Mono.error(io.grpc.Status.UNAUTHENTICATED.asRuntimeException());
      return stub.withDeadlineAfter(properties.auth().deadline().toMillis(), TimeUnit.MILLISECONDS)
          .exchangeToken(
              ExchangeOAuthTokenRequest.newBuilder()
                  .setAccessToken(token)
                  .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId("mcp-server"))
                  .build());
    };
  }
}
