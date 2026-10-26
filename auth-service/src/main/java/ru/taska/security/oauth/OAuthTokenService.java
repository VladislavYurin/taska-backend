package ru.taska.security.oauth;

import com.google.protobuf.Timestamp;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.oauth.v1.ExchangeOAuthTokenResponse;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.UserContext;
import ru.taska.entity.UserStatus;
import ru.taska.repository.UserRepository;
import ru.taska.util.UserContexts;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OAuthTokenService {
  static final String TYPE = "taska-delegation+jwt",
      ISSUER = "urn:taska:auth:delegation",
      AUDIENCE = "urn:taska:gateway";
  private final OAuthBridgeProperties properties;
  private final OAuthIntrospectionClient introspection;
  private final UserRepository users;
  private final javax.crypto.SecretKey key;

  public OAuthTokenService(
      OAuthBridgeProperties properties,
      OAuthIntrospectionClient introspection,
      UserRepository users) {
    this.properties = properties;
    this.introspection = introspection;
    this.users = users;
    key =
        properties.enabled()
            ? Keys.hmacShaKeyFor(
                properties.delegationSigningSecret().getBytes(StandardCharsets.UTF_8))
            : null;
  }

  public Mono<UserContext> validateOriginal(String token) {
    return introspection
        .introspect(token)
        .flatMap(identity -> context(identity, AccessTokenKind.ACCESS_TOKEN_KIND_OAUTH));
  }

  public Mono<ExchangeOAuthTokenResponse> exchange(String token) {
    return introspection
        .introspect(token)
        .flatMap(
            identity ->
                context(identity, AccessTokenKind.ACCESS_TOKEN_KIND_OAUTH)
                    .map(
                        user ->
                            ExchangeOAuthTokenResponse.newBuilder()
                                .setUserContext(user)
                                .setDelegationToken(issue(identity))
                                .build()));
  }

  public Mono<UserContext> validateSubject(String subject) {
    return Mono.fromCallable(() -> UUID.fromString(subject))
        .onErrorMap(error -> OAuthIntrospectionClient.invalid())
        .flatMap(id -> users.findById(id))
        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
        .switchIfEmpty(Mono.error(OAuthIntrospectionClient.invalid()))
        .map(user -> UserContexts.builder(user).build());
  }

  public Mono<UserContext> validateDelegation(String token) {
    return Mono.fromCallable(() -> decode(token))
        .flatMap(
            claims -> {
              UUID subject = UUID.fromString(claims.getSubject());
              String grant = claims.get("parent_grant", String.class),
                  tokenId = claims.get("parent_token", String.class);
              List<?> rawScopes = claims.get("scopes", List.class);
              if (rawScopes == null
                  || rawScopes.isEmpty()
                  || rawScopes.size() > 2
                  || rawScopes.stream()
                      .anyMatch(
                          scope ->
                              !(scope instanceof String)
                                  || !Set.of("read", "write").contains(scope)))
                return Mono.error(OAuthIntrospectionClient.invalid());
              List<String> scopes = rawScopes.stream().map(String.class::cast).toList();
              if (!scopes.contains("read")) return Mono.error(OAuthIntrospectionClient.invalid());
              return introspection
                  .validateParent(grant, tokenId)
                  .filter(
                      parent ->
                          parent.subject().equals(subject)
                              && parent.grantId().toString().equals(grant)
                              && parent.tokenId().toString().equals(tokenId)
                              && parent.scopes().containsAll(scopes))
                  .switchIfEmpty(Mono.error(OAuthIntrospectionClient.invalid()))
                  .flatMap(
                      parent ->
                          context(
                              new OAuthGrantIdentity(
                                  parent.subject(),
                                  parent.grantId(),
                                  parent.tokenId(),
                                  scopes,
                                  claims.getExpiration().toInstant()),
                              AccessTokenKind.ACCESS_TOKEN_KIND_DELEGATION));
            })
        .onErrorMap(
            error ->
                error instanceof ru.taska.exception.DomainException
                    ? error
                    : OAuthIntrospectionClient.invalid());
  }

  public static boolean looksDelegated(String token) {
    if (token == null || token.length() > 8192) return false;
    int dot = token.indexOf('.');
    if (dot < 1 || dot > 1024) return false;
    try {
      var header =
          JsonMapper.builder()
              .build()
              .readTree(Base64.getUrlDecoder().decode(token.substring(0, dot)));
      return TYPE.equals(header.path("typ").asText(""));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private String issue(OAuthGrantIdentity identity) {
    if (!properties.enabled() || key == null) throw OAuthIntrospectionClient.invalid();
    Instant now = Instant.now(), expires = now.plusSeconds(60);
    if (identity.expiresAt().isBefore(expires)) expires = identity.expiresAt();
    return Jwts.builder()
        .header()
        .type(TYPE)
        .and()
        .issuer(ISSUER)
        .subject(identity.subject().toString())
        .audience()
        .add(AUDIENCE)
        .and()
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(expires))
        .claim("parent_grant", identity.grantId().toString())
        .claim("parent_token", identity.tokenId().toString())
        .claim("scopes", identity.scopes())
        .claim("channel", "MCP")
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  private Claims decode(String token) {
    if (!properties.enabled() || key == null || !looksDelegated(token))
      throw OAuthIntrospectionClient.invalid();
    var signed =
        Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token);
    Claims claims = signed.getPayload();
    Instant now = Instant.now();
    if (!TYPE.equals(signed.getHeader().getType())
        || !"HS256".equals(signed.getHeader().getAlgorithm())
        || !Set.of(AUDIENCE).equals(claims.getAudience())
        || !"MCP".equals(claims.get("channel"))
        || claims.getIssuedAt() == null
        || claims.getExpiration() == null
        || claims.getIssuedAt().toInstant().isAfter(now.plusSeconds(5))
        || !claims.getExpiration().toInstant().isAfter(now)
        || claims
            .getExpiration()
            .toInstant()
            .isAfter(claims.getIssuedAt().toInstant().plusSeconds(60)))
      throw OAuthIntrospectionClient.invalid();
    return claims;
  }

  private Mono<UserContext> context(OAuthGrantIdentity identity, AccessTokenKind kind) {
    return users
        .findById(identity.subject())
        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
        .switchIfEmpty(Mono.error(OAuthIntrospectionClient.invalid()))
        .map(
            user ->
                UserContexts.builder(user)
                    .setTokenKind(kind)
                    .setTokenId(identity.tokenId().toString())
                    .addAllScopes(identity.scopes())
                    .setTokenExpiresAt(
                        Timestamp.newBuilder()
                            .setSeconds(identity.expiresAt().getEpochSecond())
                            .build())
                    .build());
  }
}
