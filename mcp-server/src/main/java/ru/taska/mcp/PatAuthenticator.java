package ru.taska.mcp;

import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.UserContext;

@FunctionalInterface
public interface PatAuthenticator {
  Mono<UserContext> authenticate(String token, String requestId);
}
