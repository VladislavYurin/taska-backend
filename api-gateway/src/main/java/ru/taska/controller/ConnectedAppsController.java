package ru.taska.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.ConnectedAppsApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.ConnectedAppsResponseDto;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcConnectedAppsClient;

@RestController
@RequiredArgsConstructor
public class ConnectedAppsController implements ConnectedAppsApi {
  private final GatewayRequestExecutor executor;
  private final BearerTokenExtractor tokens;
  private final GrpcConnectedAppsClient client;

  @Override
  public Mono<ResponseEntity<ConnectedAppsResponseDto>> listConnectedApps(
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
  public Mono<ResponseEntity<Void>> revokeConnectedApp(UUID appId, ServerWebExchange exchange) {
    return executor.execute(
        exchange,
        EndpointSecurity.JWT_SESSION_REQUIRED,
        context ->
            client
                .revoke(appId, tokens.extract(exchange, context.requestId()), context)
                .thenReturn(
                    ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()));
  }
}
