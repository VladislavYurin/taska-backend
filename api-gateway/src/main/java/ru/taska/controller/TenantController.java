package ru.taska.controller;

import io.grpc.Status;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.TenantApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.RenameTenantRequestDto;
import ru.taska.domain.dto.TenantDto;
import ru.taska.domain.dto.TenantPageDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.TenantRegistryClient;
import mapper.GrpcErrorDetails;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class TenantController implements TenantApi {
  private static final int MAX_PATCH_BYTES = 16 * 1024;
  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Pattern IF_MATCH = Pattern.compile("^\"([1-9][0-9]{0,18})\"$");
  private static final ObjectMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final GatewayRequestExecutor executor;
  private final TenantRegistryClient tenantClient;

  public TenantController(GatewayRequestExecutor executor, TenantRegistryClient tenantClient) {
    this.executor = executor;
    this.tenantClient = tenantClient;
  }

  @Override
  public Mono<ResponseEntity<TenantPageDto>> listTenants(
      String cursor, Integer limit, ServerWebExchange exchange) {
    noStore(exchange);
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            context -> {
              int requestedLimit = limit == null ? 50 : limit;
              if (requestedLimit < 1 || requestedLimit > 100) {
                return Mono.error(validation("limit", "INVALID_LIMIT"));
              }
              if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048)) {
                return Mono.error(validation("cursor", "INVALID_CURSOR"));
              }
              return tenantClient.listTenants(cursor, requestedLimit, context);
            })
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TenantDto>> getTenant(UUID tenantId, ServerWebExchange exchange) {
    noStore(exchange);
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            context -> tenantClient.getTenant(tenantId, context))
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TenantDto>> renameTenant(
      UUID tenantId,
      String idempotencyKey,
      String ifMatch,
      Mono<RenameTenantRequestDto> body,
      ServerWebExchange exchange) {
    noStore(exchange);
    return executor
        .execute(
            exchange,
            EndpointSecurity.PROTECTED,
            context -> {
              String key = idempotencyKey(exchange);
              long expectedVersion = expectedVersion(exchange);
              return readName(exchange)
                  .flatMap(name -> tenantClient.renameTenant(tenantId, name, key, expectedVersion, context));
            })
        .map(ResponseEntity::ok);
  }

  private Mono<String> readName(ServerWebExchange exchange) {
    return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_PATCH_BYTES)
        .onErrorMap(
            org.springframework.core.io.buffer.DataBufferLimitException.class,
            error -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Tenant rename body is too large"))
        .map(this::parseName)
        .switchIfEmpty(Mono.error(validation("name", "REQUIRED")));
  }

  private String parseName(DataBuffer dataBuffer) {
    byte[] bytes;
    try {
      bytes = new byte[dataBuffer.readableByteCount()];
      dataBuffer.read(bytes);
    } finally {
      DataBufferUtils.release(dataBuffer);
    }

    JsonNode root;
    try {
      root = STRICT_JSON.readTree(bytes);
    } catch (JacksonException error) {
      throw validation("name", "INVALID_JSON");
    }
    if (root == null || !root.isObject()) throw validation("name", "INVALID_BODY");
    for (String field : root.propertyNames()) {
      if (!"name".equals(field)) throw validation(field, "UNKNOWN_FIELD");
    }
    JsonNode value = root.get("name");
    if (value == null || !value.isTextual()) throw validation("name", "INVALID_NAME");
    return value.asString();
  }

  private static String idempotencyKey(ServerWebExchange exchange) {
    var values = exchange.getRequest().getHeaders().getOrEmpty("Idempotency-Key");
    if (values.size() != 1 || !IDEMPOTENCY_KEY.matcher(values.getFirst()).matches()) {
      throw validation("Idempotency-Key", values.isEmpty() ? "REQUIRED" : "INVALID_UUID");
    }
    return values.getFirst();
  }

  private static long expectedVersion(ServerWebExchange exchange) {
    var values = exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.IF_MATCH);
    if (values.isEmpty()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("If-Match header is required")
          .asRuntimeException(GrpcErrorDetails.encode(Map.of("code", "VERSION_CONFLICT")));
    }
    if (values.size() != 1) throw validation("If-Match", "INVALID_VERSION");
    var matcher = IF_MATCH.matcher(values.getFirst());
    if (!matcher.matches()) throw validation("If-Match", "INVALID_VERSION");
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException error) {
      throw validation("If-Match", "INVALID_VERSION");
    }
  }

  private static io.grpc.StatusRuntimeException validation(String field, String reason) {
    return Status.INVALID_ARGUMENT
        .withDescription("Tenant request validation failed")
        .asRuntimeException(
            GrpcErrorDetails.encode(
                Map.of("code", "VALIDATION_FAILED", "field", field, "reason", reason)));
  }

  private static void noStore(ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("private, no-store");
  }
}