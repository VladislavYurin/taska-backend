package ru.taska.mcp;

import io.netty.channel.ChannelOption;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

@Component
public class GatewayClient {
  private final WebClient client;
  private final McpProperties properties;

  public GatewayClient(WebClient.Builder builder, McpProperties properties) {
    this.properties = properties;
    HttpClient http =
        HttpClient.create()
            .followRedirect(false)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
            .responseTimeout(properties.responseTimeout());
    this.client =
        builder
            .clone()
            .baseUrl(properties.gatewayUrl().toString())
            .clientConnector(new ReactorClientHttpConnector(http))
            .codecs(config -> config.defaultCodecs().maxInMemorySize(properties.maxResponseBytes()))
            .build();
  }

  public Mono<JsonNode> currentUser(McpRequestContext context) {
    return client
        .get()
        .uri("/api/v1/users/me")
        .headers(
            headers -> {
              headers.setBearerAuth(context.bearerToken());
              headers.set("X-Request-Id", context.requestId());
            })
        .accept(MediaType.APPLICATION_JSON)
        .exchangeToMono(this::decode)
        .timeout(properties.responseTimeout())
        .onErrorMap(
            error -> !(error instanceof GatewayToolException),
            error ->
                new GatewayToolException(
                    error instanceof java.util.concurrent.TimeoutException
                        ? "timeout"
                        : "upstream_error",
                    "Taska временно недоступна. Повторите чтение позже."));
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
    if (!response.statusCode().is2xxSuccessful()) {
      GatewayToolException error =
          switch (status) {
            case 400 ->
                new GatewayToolException("invalid_argument", "Проверьте аргументы запроса.");
            case 401 ->
                new GatewayToolException("unauthenticated", "Подключите действующий токен Taska.");
            case 403, 404 ->
                new GatewayToolException(
                    "not_found", "Объект не найден или недоступен; попробуйте search_issues.");
            case 409 ->
                new GatewayToolException("conflict", "Обновите объект перед повторным изменением.");
            case 429 ->
                new GatewayToolException(
                    "rate_limited",
                    "Достигнут лимит запросов. Повторите позже.",
                    retryDetails(response.headers().asHttpHeaders()));
            default ->
                new GatewayToolException(
                    "upstream_error", "Taska временно недоступна. Повторите позже.");
          };
      return response.releaseBody().then(Mono.error(error));
    }
    MediaType type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
    if (!MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
      return response
          .releaseBody()
          .then(
              Mono.error(
                  new GatewayToolException(
                      "upstream_error",
                      "Taska вернула некорректный ответ. Повторите чтение позже.")));
    }
    return response
        .bodyToMono(JsonNode.class)
        .filter(JsonNode::isObject)
        .switchIfEmpty(
            Mono.error(
                new GatewayToolException(
                    "upstream_error",
                    "Taska вернула пустой или некорректный ответ. Повторите чтение позже.")));
  }

  private static Map<String, Object> retryDetails(HttpHeaders headers) {
    String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (value != null && value.matches("[0-9]{1,6}")) {
      return Map.of("retry_after_seconds", Integer.parseInt(value));
    }
    return Map.of();
  }
}
