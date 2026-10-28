package ru.taska.oauth;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable registrations; only approved metadata hosts and exact redirect URIs may create clients.
 */
@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class OAuthClients implements RegisteredClientRepository {
  private final JdbcRegisteredClientRepository delegate;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final OAuthServerProperties policy;
  private final CimdDocumentFetcher fetcher;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public OAuthClients(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      OAuthServerProperties policy,
      CimdDocumentFetcher fetcher,
      PasswordEncoder encoder) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.policy = policy;
    this.fetcher = fetcher;
    delegate = new JdbcRegisteredClientRepository(jdbc);
    var previous = delegate.findByClientId(policy.introspectionClientId());
    if (previous == null
        || !encoder.matches(policy.introspectionSecret(), previous.getClientSecret())) {
      delegate.save(
          RegisteredClient.withId(
                  previous == null ? UUID.randomUUID().toString() : previous.getId())
              .clientId(policy.introspectionClientId())
              .clientName("Taska authorization introspection")
              .clientSecret(encoder.encode(policy.introspectionSecret()))
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
              .authorizationGrantType(new AuthorizationGrantType("urn:taska:introspection-only"))
              .scope("introspect")
              .build());
    }
  }

  @Override
  public void save(RegisteredClient client) {
    delegate.save(client);
  }

  @Override
  public RegisteredClient findById(String id) {
    return delegate.findById(id);
  }

  @Override
  public RegisteredClient findByClientId(String id) {
    if (id == null || id.length() > 2048) return null;
    RegisteredClient existing = delegate.findByClientId(id);
    if (!id.startsWith("https://")) return existing;
    String fetchedUntil =
        existing == null ? null : existing.getClientSettings().getSetting("taska.metadata_until");
    try {
      URI uri = URI.create(id);
      if (uri.getHost() == null
          || !policy.metadataHosts().contains(uri.getHost().toLowerCase(Locale.ROOT))
          || uri.getRawPath().isEmpty()
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getQuery() != null
          || uri.getPort() != -1 && uri.getPort() != 443) return null;
      if (existing != null && !policy.redirectUris().containsAll(existing.getRedirectUris()))
        return null;
      if (fetchedUntil != null && Long.parseLong(fetchedUntil) > Instant.now().getEpochSecond())
        return existing;
      var document = fetcher.fetch(uri);
      JsonNode metadata = mapper.readTree(document.json());
      if (!id.equals(metadata.path("client_id").asText(""))) return null;
      return register(id, metadata, document.cacheFor(), existing);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  RegisteredClient registerDcr(JsonNode metadata) {
    if (!policy.dcrEnabled())
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
    return register(UUID.randomUUID().toString(), metadata, null, null);
  }

  private static String normalizeName(String raw) {
    String name = raw.replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "").replaceAll("\\s+", " ").trim();
    int count = name.codePointCount(0, name.length());
    return count > 60 ? name.substring(0, name.offsetByCodePoints(0, 60)).stripTrailing() + "…" : name;
  }

  private RegisteredClient register(
      String id, JsonNode metadata, Duration cacheFor, RegisteredClient previous) {
    if (!metadata.isObject()
        || metadata.has("client_secret")
        || metadata.has("jwks")
        || metadata.has("jwks_uri")
        || !"none".equals(metadata.path("token_endpoint_auth_method").asText("none")))
      throw invalidMetadata();
    String name = normalizeName(metadata.path("client_name").asText(""));
    if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl))
      throw invalidMetadata();
    JsonNode redirects = metadata.path("redirect_uris");
    if (!redirects.isArray() || redirects.isEmpty() || redirects.size() > 20)
      throw invalidMetadata();
    Set<String> approved = new LinkedHashSet<>();
    for (JsonNode value : redirects) {
      if (!value.isString() || !policy.redirectUris().contains(value.asText()))
        throw invalidMetadata();
      OAuthServerProperties.validateRedirect(value.asText());
      approved.add(value.asText());
    }
    Set<String> grants = new LinkedHashSet<>();
    JsonNode requested = metadata.path("grant_types");
    if (requested.isMissingNode()) grants.add("authorization_code");
    else {
      if (!requested.isArray() || requested.isEmpty() || requested.size() > 2)
        throw invalidMetadata();
      for (JsonNode grant : requested) {
        if (!grant.isString()
            || !Set.of("authorization_code", "refresh_token").contains(grant.asText()))
          throw invalidMetadata();
        grants.add(grant.asText());
      }
    }
    if (!grants.contains("authorization_code")) throw invalidMetadata();
    JsonNode responses = metadata.path("response_types");
    if (!responses.isMissingNode()
        && (!responses.isArray()
            || responses.size() != 1
            || !"code".equals(responses.get(0).asText("")))) throw invalidMetadata();
    var settings = ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true);
    if (cacheFor != null)
      settings.setting(
          "taska.metadata_until", Long.toString(Instant.now().plus(cacheFor).getEpochSecond()));
    var builder =
        RegisteredClient.withId(previous == null ? UUID.randomUUID().toString() : previous.getId())
            .clientId(id)
            .clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .redirectUris(uris -> uris.addAll(approved))
            .clientSettings(settings.build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                    .accessTokenTimeToLive(Duration.ofMinutes(10))
                    .authorizationCodeTimeToLive(Duration.ofMinutes(2))
                    .refreshTokenTimeToLive(Duration.ofDays(30))
                    .reuseRefreshTokens(false)
                    .build())
            .scope("read")
            .scope("write");
    grants.forEach(grant -> builder.authorizationGrantType(new AuthorizationGrantType(grant)));
    if (grants.contains("refresh_token")) builder.scope("offline_access");
    var client = builder.build();
    return transactions.execute(
        status -> {
          jdbc.queryForObject(
              "SELECT id FROM oauth_registration_lock WHERE id=1 FOR UPDATE", Integer.class);
          if (previous == null
              && jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class)
                  >= policy.maxClients())
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.TEMPORARILY_UNAVAILABLE);
          // A concurrent fetch for the same CIMD identifier must update, not create another client.
          var raced = delegate.findByClientId(id);
          var result =
              raced == null || raced.getId().equals(client.getId())
                  ? client
                  : RegisteredClient.from(client).id(raced.getId()).build();
          delegate.save(result);
          return result;
        });
  }

  private static OAuth2AuthenticationException invalidMetadata() {
    return new OAuth2AuthenticationException("invalid_client_metadata");
  }
}
