package ru.taska.mcp;

import io.netty.channel.ChannelOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

@Component
public class GatewayClient {
  private final WebClient client;
  private final McpProperties properties;

  public GatewayClient(WebClient.Builder builder, McpProperties properties) {
    this(builder, properties, Optional.empty());
  }

  @Autowired
  public GatewayClient(
      WebClient.Builder builder,
      McpProperties properties,
      @Qualifier("mcpGatewaySigningFilter") Optional<ExchangeFilterFunction> signing) {
    this.properties = properties;
    HttpClient http =
        HttpClient.create()
            .disableRetry(true)
            .followRedirect(false)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
            .responseTimeout(properties.responseTimeout());
    var uris = new DefaultUriBuilderFactory(properties.gatewayUrl().toString());
    uris.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
    this.client =
        builder
            .clone()
            .baseUrl(properties.gatewayUrl().toString())
            .uriBuilderFactory(uris)
            .filters(filters -> signing.ifPresent(filters::add))
            .clientConnector(new ReactorClientHttpConnector(http))
            .codecs(config -> config.defaultCodecs().maxInMemorySize(properties.maxResponseBytes()))
            .build();
  }

  public Mono<JsonNode> currentUser(McpRequestContext context) {
    return get(GatewayEndpoint.ME, context, Map.of(), Map.of());
  }

  public Mono<JsonNode> get(
      GatewayEndpoint endpoint,
      McpRequestContext context,
      Map<String, ?> path,
      Map<String, ?> query) {
    if (!endpoint.queries().containsAll(query.keySet()))
      return Mono.error(new IllegalArgumentException("Unsupported gateway query"));
    return client
        .get()
        .uri(
            builder -> {
              builder.path(endpoint.path());
              Map<String, Object> variables = new LinkedHashMap<>(path);
              int index = 0;
              for (var value : query.entrySet()) {
                if (value.getValue() == null) continue;
                String variable = "mcpQuery" + index++;
                builder.queryParam(value.getKey(), "{" + variable + "}");
                variables.put(variable, value.getValue());
              }
              return builder.build(variables);
            })
        .headers(
            headers -> {
              headers.setBearerAuth(context.bearerToken());
              headers.set("X-Request-Id", context.requestId());
              if (context.metadata() != null && context.metadata().traceparent() != null)
                headers.set("traceparent", context.metadata().traceparent());
            })
        .accept(MediaType.APPLICATION_JSON)
        .exchangeToMono(this::decode)
        .timeout(properties.responseTimeout())
        .onErrorMap(
            error -> !(error instanceof GatewayToolException),
            error ->
                new GatewayToolException(
                    isTimeout(error) ? "timeout" : "upstream_error",
                    "Taska временно недоступна. Повторите чтение позже."));
  }

  public Mono<JsonNode> write(
      GatewayMutation operation,
      McpRequestContext context,
      Map<String, ?> path,
      JsonNode body,
      GatewayWriteOptions options) {
    var request =
        client
            .method(operation.method())
            .uri(builder -> builder.path(operation.path()).build(path))
            .headers(
                headers -> {
                  headers.setBearerAuth(context.bearerToken());
                  headers.set("X-Request-Id", context.requestId());
                  if (context.metadata() != null && context.metadata().traceparent() != null)
                    headers.set("traceparent", context.metadata().traceparent());
                  if (options.version() != null)
                    headers.set(HttpHeaders.IF_MATCH, options.version().toString());
                  if (options.idempotencyKey() != null)
                    headers.set("Idempotency-Key", options.idempotencyKey().toString());
                })
            .accept(MediaType.APPLICATION_JSON);
    var ready =
        body == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    return ready
        .exchangeToMono(
            response -> {
              if (operation.emptyResponse() && response.statusCode().value() == 204)
                return response
                    .releaseBody()
                    .thenReturn(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
              return decode(response);
            })
        .timeout(properties.responseTimeout())
        .onErrorMap(
            error -> {
              boolean unknown =
                  !(error instanceof GatewayToolException)
                      || "upstream_error".equals(((GatewayToolException) error).code());
              if (!unknown) return error;
              String message =
                  options.idempotencyKey() != null
                      ? "Результат создания неизвестен. Повтор допустим только с тем же"
                          + " idempotency_key и аргументами."
                      : "Результат записи неизвестен. Не повторяйте автоматически: перечитайте"
                          + " объект, комментарии, worklogs или связи и уточните намерение при"
                          + " неоднозначности.";
              return new GatewayToolException(
                  isTimeout(error) ? "timeout" : "upstream_error",
                  message,
                  Map.of("reason", "WRITE_OUTCOME_UNKNOWN"));
            });
  }

  public Mono<Boolean> healthy() {
    return client
        .get()
        .uri("/actuator/health")
        .exchangeToMono(
            response -> response.releaseBody().thenReturn(response.statusCode().is2xxSuccessful()))
        .timeout(properties.responseTimeout())
        .onErrorReturn(false);
  }

  private Mono<JsonNode> decode(ClientResponse response) {
    int status = response.statusCode().value();
    MediaType type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
    if (!response.statusCode().is2xxSuccessful()) {
      if (status < 500 && MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
        return response
            .bodyToMono(JsonNode.class)
            .defaultIfEmpty(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())
            .onErrorReturn(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())
            .flatMap(body -> Mono.error(error(status, body, response.headers().asHttpHeaders())));
      }
      return response
          .releaseBody()
          .then(Mono.error(error(status, null, response.headers().asHttpHeaders())));
    }
    if (!MediaType.APPLICATION_JSON.isCompatibleWith(type))
      return response
          .releaseBody()
          .then(
              Mono.error(
                  new GatewayToolException(
                      "upstream_error",
                      "Taska вернула некорректный ответ. Повторите чтение позже.")));
    return response
        .bodyToMono(JsonNode.class)
        .filter(JsonNode::isObject)
        .switchIfEmpty(
            Mono.error(
                new GatewayToolException(
                    "upstream_error",
                    "Taska вернула пустой или некорректный ответ. Повторите чтение позже.")));
  }

  private static GatewayToolException error(int status, JsonNode body, HttpHeaders headers) {
    Map<String, Object> details = new LinkedHashMap<>();
    JsonNode source = body == null ? null : body.get("details");
    String reason = text(source, "reason");
    if (reason != null && reason.matches("[A-Z0-9_]{1,64}")) details.put("reason", reason);
    String scope = text(source, "requiredScope");
    if ("read".equals(scope) || "write".equals(scope)) details.put("required_scope", scope);
    String version = text(source, "currentVersion");
    if (version == null && status == 409) version = text(body, "version");
    if (version != null && version.matches("[0-9]{1,9}"))
      details.put("current_version", Integer.parseInt(version));
    String currentStatus = text(source, "currentStatus");
    if (currentStatus == null && status == 409) currentStatus = text(body, "status");
    if (currentStatus != null && currentStatus.matches("[A-Za-z0-9_]{1,64}"))
      details.put("current_status", currentStatus);
    String retry = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (retry == null) retry = text(source, "retryAfterSeconds");
    if (retry != null && retry.matches("[0-9]{1,6}"))
      details.put("retry_after_seconds", Integer.parseInt(retry));
    if (status == 403 && "INSUFFICIENT_SCOPE".equals(reason))
      return new GatewayToolException(
          "insufficient_scope", "Подключите токен с требуемым scope.", details);
    return switch (status) {
      case 400 ->
          new GatewayToolException(
              body != null && "FAILED_PRECONDITION".equals(text(body, "code"))
                  ? "failed_precondition"
                  : "invalid_argument",
              "Проверьте аргументы и условия операции.",
              details);
      case 401 ->
          new GatewayToolException("unauthenticated", "Подключите действующий токен Taska.");
      case 403, 404 -> notFound();
      case 409 ->
          new GatewayToolException(
              "conflict", "Обновите объект перед повторным изменением.", details);
      case 429 ->
          new GatewayToolException(
              "rate_limited", "Достигнут лимит запросов. Повторите позже.", details);
      default ->
          new GatewayToolException("upstream_error", "Taska временно недоступна. Повторите позже.");
    };
  }

  public static GatewayToolException notFound() {
    return new GatewayToolException(
        "not_found", "Объект не найден или недоступен; попробуйте search_issues.");
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node == null ? null : node.get(name);
    return value != null && (value.isString() || value.isNumber()) ? value.asText() : null;
  }

  private static boolean isTimeout(Throwable error) {
    for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
      if (error instanceof java.util.concurrent.TimeoutException
          || error instanceof io.netty.handler.timeout.ReadTimeoutException) return true;
    return false;
  }
}
