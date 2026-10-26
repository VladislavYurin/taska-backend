package ru.taska.controller;

import io.grpc.Status;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.PersonalAccessTokenApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.dto.CreatePersonalAccessTokenRequestDto;
import ru.taska.domain.dto.CreatePersonalAccessTokenResponseDto;
import ru.taska.domain.dto.PersonalAccessTokensResponseDto;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.PersonalTokenCreationLimiter;
import ru.taska.transport.grpc.GrpcPersonalAccessTokenClient;

@RestController
@RequiredArgsConstructor
public class PersonalAccessTokenController implements PersonalAccessTokenApi {
  private final GatewayRequestExecutor executor;
  private final BearerTokenExtractor tokens;
  private final GrpcPersonalAccessTokenClient client;
  private final PersonalTokenCreationLimiter limiter;

  @Override
  public Mono<ResponseEntity<CreatePersonalAccessTokenResponseDto>> createPersonalAccessToken(
      Mono<CreatePersonalAccessTokenRequestDto> body, ServerWebExchange exchange) {
    return executor.execute(
        exchange,
        EndpointSecurity.JWT_SESSION_REQUIRED,
        context -> {
          if (context.userContext().status() != GatewayUserStatus.ACTIVE) {
            return Mono.error(
                Status.UNAUTHENTICATED
                    .withDescription("An active account is required")
                    .asRuntimeException());
          }
          limiter.acquire(context.userContext().userId());
          String session = tokens.extract(exchange, context.requestId());
          return body.flatMap(value -> client.create(value, session, context))
              .map(
                  value ->
                      ResponseEntity.status(HttpStatus.CREATED)
                          .cacheControl(CacheControl.noStore())
                          .body(value));
        });
  }

  @Override
  public Mono<ResponseEntity<PersonalAccessTokensResponseDto>> listPersonalAccessTokens(
      ServerWebExchange exchange) {
    return executor.execute(
        exchange,
        EndpointSecurity.JWT_SESSION_REQUIRED,
        context ->
            client
                .list(tokens.extract(exchange, context.requestId()), context)
                .map(
                    value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)));
  }

  @Override
  public Mono<ResponseEntity<Void>> revokePersonalAccessToken(
      UUID tokenId, ServerWebExchange exchange) {
    return executor.execute(
        exchange,
        EndpointSecurity.JWT_SESSION_REQUIRED,
        context ->
            client
                .revoke(tokenId.toString(), tokens.extract(exchange, context.requestId()), context)
                .thenReturn(
                    ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()));
  }
}
