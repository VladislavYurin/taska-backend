package ru.taska.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.TenantPersonalTokenApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.TenantPersonalTokenRequestDto;
import ru.taska.domain.dto.TenantPersonalTokenCreatedDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.TenantPersonalTokenClient;

@RestController
public class TenantPersonalTokenController implements TenantPersonalTokenApi {
  private final GatewayRequestExecutor executor;
  private final TenantPersonalTokenClient client;

  public TenantPersonalTokenController(GatewayRequestExecutor executor, TenantPersonalTokenClient client) {
    this.executor = executor;
    this.client = client;
  }

  @Override
  public Mono<ResponseEntity<TenantPersonalTokenCreatedDto>> createTenantPersonalToken(
      UUID idempotencyKey, Mono<TenantPersonalTokenRequestDto> body, ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    return executor.execute(exchange, EndpointSecurity.JWT_SESSION_REQUIRED, context -> {
      var keys = exchange.getRequest().getHeaders().getOrEmpty("Idempotency-Key");
      if (keys.size() != 1 || !idempotencyKey.toString().equals(keys.getFirst())) return Mono.error(invalid());
      return body.switchIfEmpty(Mono.error(invalid()))
          .flatMap(value -> client.create(value, idempotencyKey, context))
          .map(value -> ResponseEntity.status(201).body(value));
    });
  }

  private static io.grpc.StatusRuntimeException invalid() {
    return io.grpc.Status.INVALID_ARGUMENT.withDescription("Invalid token request")
        .asRuntimeException(mapper.GrpcErrorDetails.encode(Map.of("code", "VALIDATION_FAILED")));
  }
}
