package ru.taska.mcp;

import reactor.core.publisher.Mono;
import ru.taska.api.auth.oauth.v1.ExchangeOAuthTokenResponse;

@FunctionalInterface
public interface McpOAuthAuthenticator {
  Mono<ExchangeOAuthTokenResponse> authenticate(String token, String requestId);
}
