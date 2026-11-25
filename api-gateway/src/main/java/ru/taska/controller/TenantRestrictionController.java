package ru.taska.controller;

import io.grpc.Status;
import java.util.*;
import lombok.RequiredArgsConstructor;
import mapper.GrpcErrorDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.TenantRestrictionsApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.*;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.TenantRegistryClient;

@RestController
@RequiredArgsConstructor
public class TenantRestrictionController implements TenantRestrictionsApi {
  private final GatewayRequestExecutor executor;
  private final TenantRegistryClient client;

  @Override
  public Mono<ResponseEntity<TenantDto>> pauseTenant(
      UUID tenantId,
      Mono<ConfirmedActionDto> payload,
      String idempotencyKey,
      String ifMatch,
      ServerWebExchange exchange) {
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            context -> {
              var mutation = mutation(exchange);
              return payload
                  .switchIfEmpty(Mono.error(invalid()))
                  .flatMap(
                      body ->
                          client.pauseTenant(
                              tenantId,
                              mutation.key(),
                              mutation.version(),
                              Boolean.TRUE.equals(body.getConfirmed()),
                              context));
            })
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TenantDto>> resumeTenant(
      UUID tenantId, String idempotencyKey, String ifMatch, ServerWebExchange exchange) {
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            context -> {
              var mutation = mutation(exchange);
              return client.resumeTenant(tenantId, mutation.key(), mutation.version(), context);
            })
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TenantDto>> setPlatformBlock(
      UUID tenantId,
      Mono<AdminRestrictionRequestDto> payload,
      String idempotencyKey,
      String ifMatch,
      ServerWebExchange exchange) {
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            context -> {
              var mutation = mutation(exchange);
              return payload
                  .switchIfEmpty(Mono.error(invalid()))
                  .flatMap(
                      body -> {
                        if (body.getEnabled() == null
                            || body.getReason() == null
                            || body.getReason().isBlank()
                            || body.getReason().length() > 2000) return Mono.error(invalid());
                        return client.setPlatformBlock(
                            tenantId,
                            mutation.key(),
                            mutation.version(),
                            body.getEnabled(),
                            body.getReason(),
                            Boolean.TRUE.equals(body.getConfirmed()),
                            context);
                      });
            })
        .map(ResponseEntity::ok);
  }

  private record Mutation(String key, long version) {}

  private static Mutation mutation(ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("private, no-store");
    var versions = exchange.getRequest().getHeaders().getOrEmpty("If-Match");
    if (versions.isEmpty())
      throw Status.FAILED_PRECONDITION
          .withDescription("Tenant version required")
          .asRuntimeException(GrpcErrorDetails.encode(Map.of("code", "VERSION_CONFLICT")));
    if (versions.size() != 1 || !versions.getFirst().matches("\\\"[1-9][0-9]{0,18}\\\""))
      throw invalid();
    var keys = exchange.getRequest().getHeaders().getOrEmpty("Idempotency-Key");
    if (keys.size() != 1) throw invalid();
    try {
      String key = UUID.fromString(keys.getFirst()).toString();
      if (!key.equals(keys.getFirst())) throw invalid();
      long version =
          Long.parseLong(versions.getFirst().substring(1, versions.getFirst().length() - 1));
      return new Mutation(key, version);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static io.grpc.StatusRuntimeException invalid() {
    return Status.INVALID_ARGUMENT
        .withDescription("Invalid tenant restriction request")
        .asRuntimeException();
  }
}
