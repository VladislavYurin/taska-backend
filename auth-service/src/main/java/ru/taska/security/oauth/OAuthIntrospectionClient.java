package ru.taska.security.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.JsonNode;

@Component
public class OAuthIntrospectionClient {
  private final OAuthBridgeProperties properties;
  private final WebClient client;

  public OAuthIntrospectionClient(OAuthBridgeProperties properties) {
    this.properties = properties;
    client =
        WebClient.builder()
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .followRedirect(false)
                        .disableRetry(true)
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                        .responseTimeout(Duration.ofSeconds(2))))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(65536))
            .build();
  }

  Mono<OAuthGrantIdentity> introspect(String token) {
    if (!properties.enabled() || token == null || !token.matches("tsk_oat_[A-Za-z0-9_-]{43}"))
      return Mono.error(invalid());
    return client
        .post()
        .uri(properties.introspectionUri())
        .headers(
            headers ->
                headers.setBasicAuth(
                    properties.introspectionClientId(), properties.introspectionSecret()))
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData("token", token).with("token_type_hint", "access_token"))
        .retrieve()
        .bodyToMono(JsonNode.class)
        .timeout(Duration.ofSeconds(2))
        .map(this::identity)
        .switchIfEmpty(Mono.error(invalid()))
        .onErrorMap(error -> error instanceof DomainException ? error : unavailable());
  }

  Mono<OAuthGrantIdentity> validateParent(String grantId, String tokenId) {
    if (!properties.enabled()) return Mono.error(invalid());
    return client
        .post()
        .uri(properties.grantValidationUri())
        .headers(
            headers ->
                headers.setBasicAuth(
                    properties.introspectionClientId(), properties.introspectionSecret()))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("grant_id", grantId, "token_id", tokenId))
        .retrieve()
        .bodyToMono(JsonNode.class)
        .timeout(Duration.ofSeconds(2))
        .map(this::identity)
        .switchIfEmpty(Mono.error(invalid()))
        .onErrorMap(error -> error instanceof DomainException ? error : unavailable());
  }

  private OAuthGrantIdentity identity(JsonNode node) {
    try {
      if (!node.path("active").asBoolean(false)
          || !properties.issuer().toString().equals(node.path("iss").asText(""))) throw invalid();
      JsonNode aud = node.path("aud");
      boolean match =
          aud.isString()
              ? properties.resource().toString().equals(aud.asText())
              : aud.isArray()
                  && aud.size() == 1
                  && properties.resource().toString().equals(aud.get(0).asText(""));
      if (!match) throw invalid();
      Instant expires = Instant.ofEpochSecond(node.path("exp").asLong(0));
      if (!expires.isAfter(Instant.now())) throw invalid();
      List<String> scopes = new ArrayList<>();
      for (String scope : node.path("scope").asText("").split(" ")) {
        if (!List.of("read", "write", "offline_access").contains(scope)) throw invalid();
        if (!"offline_access".equals(scope)) scopes.add(scope);
      }
      if (!scopes.contains("read") || scopes.size() > 2) throw invalid();
      return new OAuthGrantIdentity(
          UUID.fromString(node.path("sub").asText("")),
          UUID.fromString(node.path("grant_id").asText("")),
          UUID.fromString(node.path("jti").asText("")),
          List.copyOf(scopes),
          expires);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  public static DomainException invalid() {
    return new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid OAuth authorization");
  }

  private static DomainException unavailable() {
    return new DomainException(DomainStatus.UNAVAILABLE, "OAuth authorization service unavailable");
  }
}
