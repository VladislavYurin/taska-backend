package ru.taska.controller;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.domain.EndpointSecurity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.ProjectDeletionClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class ProjectDeletionController {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final GatewayRequestExecutor executor;
  private final ProjectDeletionClient client;

  public ProjectDeletionController(GatewayRequestExecutor executor, ProjectDeletionClient client) {
    this.executor = executor;
    this.client = client;
  }

  @PostMapping("/api/v1/tenants/{tenantId}/projects/{projectId}/deletion")
  public Mono<ResponseEntity<Map<String, Object>>> delete(
      @PathVariable UUID tenantId, @PathVariable UUID projectId, ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            c -> {
              UUID key = uuidHeader(exchange, "Idempotency-Key");
              int expected = expected(exchange);
              return DataBufferUtils.join(exchange.getRequest().getBody(), 16384)
                  .onErrorMap(
                      org.springframework.core.io.buffer.DataBufferLimitException.class,
                      e -> invalid())
                  .map(
                      buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        try {
                          buffer.read(bytes);
                        } finally {
                          DataBufferUtils.release(buffer);
                        }
                        JsonNode body;
                        try {
                          body = JSON.readTree(bytes);
                        } catch (RuntimeException e) {
                          throw invalid();
                        }
                        if (body == null
                            || !body.isObject()
                            || body.size() != 2
                            || !body.has("confirmed")
                            || !body.get("confirmed").isBoolean()
                            || !body.has("confirmedProjectKey")
                            || !body.get("confirmedProjectKey").isTextual()
                            || body.get("confirmedProjectKey").asString().isEmpty()
                            || body.get("confirmedProjectKey").asString().length() > 64)
                          throw invalid();
                        for (String field : body.propertyNames())
                          if (!Set.of("confirmed", "confirmedProjectKey").contains(field))
                            throw invalid();
                        return body;
                      })
                  .switchIfEmpty(Mono.error(invalid()))
                  .flatMap(
                      body ->
                          client.start(
                              tenantId,
                              projectId,
                              key,
                              expected,
                              body.get("confirmed").asBoolean(),
                              body.get("confirmedProjectKey").asString(),
                              c));
            })
        .map(op -> ResponseEntity.accepted().body(response(op)));
  }

  @GetMapping("/api/v1/tenants/{tenantId}/operations/{operationId}")
  public Mono<ResponseEntity<Map<String, Object>>> get(
      @PathVariable UUID tenantId, @PathVariable UUID operationId, ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    return executor
        .execute(exchange, EndpointSecurity.PROTECTED, c -> client.get(tenantId, operationId, c))
        .map(op -> ResponseEntity.ok(response(op)));
  }

  private static Map<String, Object> response(Operation op) {
    var result =
        new java.util.LinkedHashMap<String, Object>(
            Map.of(
                "id",
                op.getId(),
                "type",
                op.getType(),
                "state",
                op.getState().name().replace("STATE_", ""),
                "tenantId",
                op.getTenantId(),
                "resourceId",
                op.getResourceId(),
                "createdAt",
                op.getCreatedAt(),
                "updatedAt",
                op.getUpdatedAt()));
    if (op.hasFailureCode())
      result.put("failureCode", op.getFailureCode().name().replace("ERROR_CODE_", ""));
    return result;
  }

  private static UUID uuidHeader(ServerWebExchange e, String name) {
    var v = e.getRequest().getHeaders().getOrEmpty(name);
    if (v.size() != 1) throw invalid();
    try {
      var id = UUID.fromString(v.getFirst());
      if (!id.toString().equals(v.getFirst())) throw invalid();
      return id;
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static int expected(ServerWebExchange e) {
    var v = e.getRequest().getHeaders().getOrEmpty("If-Match");
    if (v.isEmpty())
      throw new DomainException(
          DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT", Map.of("code", "VERSION_CONFLICT"));
    if (v.size() != 1 || !v.getFirst().matches("\"(?:0|[1-9][0-9]{0,9})\"")) throw invalid();
    try {
      return Integer.parseInt(v.getFirst().substring(1, v.getFirst().length() - 1));
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  private static DomainException invalid() {
    return new DomainException(
        DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", Map.of("code", "VALIDATION_FAILED"));
  }
}
