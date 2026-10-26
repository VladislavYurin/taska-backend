package ru.taska.security.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.oauth.v1.OAuthIdentityServiceGrpc;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.entity.*;
import ru.taska.exception.*;
import ru.taska.repository.UserRepository;
import security.OAuthServiceCredentials;
import tools.jackson.databind.json.JsonMapper;

class OAuthTokenSecurityTest {
  static final String OAT = "tsk_oat_" + "A".repeat(43),
      SUBJECT = UUID.randomUUID().toString(),
      GRANT = UUID.randomUUID().toString(),
      TOKEN = UUID.randomUUID().toString();
  static final String SECRET = "fixture-delegation-jwt-secret-32chars",
      IDENTITY = "fixture-oauth-identity-secret-32chars",
      EXCHANGE = "fixture-mcp-exchange-secret-32chars";
  HttpServer server;
  OAuthBridgeProperties properties;
  OAuthTokenService service;
  UserRepository users;
  final AtomicReference<Map<String, Object>> claims = new AtomicReference<>();
  final AtomicInteger introspections = new AtomicInteger(), parentChecks = new AtomicInteger();
  final JsonMapper mapper = JsonMapper.builder().build();

  @BeforeEach
  void prepare() throws Exception {
    claims.set(
        Map.of(
            "active",
            true,
            "iss",
            "https://auth.example.test",
            "aud",
            List.of("https://mcp.example.test/mcp"),
            "exp",
            Instant.now().plusSeconds(600).getEpochSecond(),
            "sub",
            SUBJECT,
            "grant_id",
            GRANT,
            "jti",
            TOKEN,
            "scope",
            "read write offline_access"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        request -> {
          String basic =
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(
                          "auth-service:fixture-introspection-service-secret-32"
                              .getBytes(StandardCharsets.UTF_8));
          if (!basic.equals(request.getRequestHeaders().getFirst("Authorization"))) {
            request.sendResponseHeaders(401, -1);
            request.close();
            return;
          }
          String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (request.getRequestURI().getPath().equals("/introspect")) {
            introspections.incrementAndGet();
            assertThat(body).contains(OAT);
          } else {
            parentChecks.incrementAndGet();
            assertThat(body).doesNotContain(OAT).contains(GRANT, TOKEN);
          }
          byte[] bytes = mapper.writeValueAsBytes(claims.get());
          request.getResponseHeaders().set("Content-Type", "application/json");
          request.sendResponseHeaders(200, bytes.length);
          request.getResponseBody().write(bytes);
          request.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    properties =
        new OAuthBridgeProperties(
            true,
            URI.create("https://auth.example.test"),
            URI.create("https://mcp.example.test/mcp"),
            URI.create(base + "/introspect"),
            URI.create(base + "/parent"),
            "auth-service",
            "fixture-introspection-service-secret-32",
            IDENTITY,
            EXCHANGE,
            SECRET);
    users = mock(UserRepository.class);
    when(users.findById(UUID.fromString(SUBJECT)))
        .thenReturn(
            Mono.just(
                User.builder()
                    .id(UUID.fromString(SUBJECT))
                    .email("owner@example.test")
                    .displayName("Owner")
                    .status(UserStatus.ACTIVE)
                    .build()));
    service = new OAuthTokenService(properties, new OAuthIntrospectionClient(properties), users);
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  @Test
  void exchangeNeverPassesOpaqueTokenAndRevocationRejectsNextDelegatedRequest() {
    var result = service.exchange(OAT).block();
    assertThat(result.getUserContext().getTokenKind())
        .isEqualTo(AccessTokenKind.ACCESS_TOKEN_KIND_OAUTH);
    assertThat(result.getDelegationToken()).doesNotContain(OAT);
    var jwt =
        Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(result.getDelegationToken());
    assertThat(jwt.getPayload().getAudience()).containsExactly("urn:taska:gateway");
    assertThat(jwt.getPayload().getExpiration().toInstant())
        .isBeforeOrEqualTo(jwt.getPayload().getIssuedAt().toInstant().plusSeconds(60));
    var gateway = service.validateDelegation(result.getDelegationToken()).block();
    assertThat(gateway.getTokenKind()).isEqualTo(AccessTokenKind.ACCESS_TOKEN_KIND_DELEGATION);
    assertThat(gateway.getTokenId()).isEqualTo(TOKEN);
    assertThat(gateway.getScopesList()).containsExactly("read", "write");
    claims.set(Map.of("active", false));
    reject(service.validateDelegation(result.getDelegationToken()));
    assertThat(introspections).hasValue(1);
    assertThat(parentChecks).hasValue(2);
  }

  @Test
  void foreignAudienceMalformedOpaqueAndInactiveUserAreRejected() {
    var invalid = new HashMap<>(claims.get());
    invalid.put("aud", List.of("https://other.example/mcp"));
    claims.set(invalid);
    reject(service.exchange(OAT));
    assertThat(parentChecks).hasValue(0);
    reject(service.exchange("eyJ.external.jwt"));
    assertThat(introspections).hasValue(1);
    claims.set(
        new HashMap<>(invalid) {
          {
            put("aud", List.of("https://mcp.example.test/mcp"));
          }
        });
    when(users.findById(UUID.fromString(SUBJECT)))
        .thenReturn(
            Mono.just(
                User.builder().id(UUID.fromString(SUBJECT)).status(UserStatus.BLOCKED).build()));
    reject(service.exchange(OAT));
  }

  @Test
  void forgedJwtWrongAudienceLifetimeAndScopeEscalationFailBeforeUse() {
    for (String jwt :
        List.of(
            signed("urn:wrong", 60, List.of("read"), SECRET),
            signed("urn:taska:gateway", 61, List.of("read"), SECRET),
            signed("urn:taska:gateway", 60, List.of("read", "admin"), SECRET),
            signed(
                "urn:taska:gateway",
                60,
                List.of("read"),
                "wrong-key-also-at-least-32characters"))) {
      reject(service.validateDelegation(jwt));
    }
    assertThat(parentChecks).hasValue(0);
    var restricted = new HashMap<>(claims.get());
    restricted.put("scope", "read");
    claims.set(restricted);
    reject(
        service.validateDelegation(
            signed("urn:taska:gateway", 60, List.of("read", "write"), SECRET)));
    assertThat(parentChecks).hasValue(1);
  }

  @Test
  void exchangeClampsDelegationToTheParentAndValidationRejectsAShortenedOrExpiredParent() {
    var shortParent = new HashMap<>(claims.get());
    long parentExpiry = Instant.now().plusSeconds(20).getEpochSecond();
    shortParent.put("exp", parentExpiry);
    claims.set(shortParent);
    var issued = service.exchange(OAT).block();
    var jwt =
        Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(issued.getDelegationToken());
    assertThat(jwt.getPayload().getExpiration().toInstant().getEpochSecond())
        .isEqualTo(parentExpiry);
    assertThat(
            service
                .validateDelegation(issued.getDelegationToken())
                .block()
                .getTokenExpiresAt()
                .getSeconds())
        .isEqualTo(parentExpiry);
    shortParent.put("exp", parentExpiry - 10);
    claims.set(Map.copyOf(shortParent));
    reject(service.validateDelegation(issued.getDelegationToken()));
    shortParent.put("exp", Instant.now().minusSeconds(1).getEpochSecond());
    claims.set(Map.copyOf(shortParent));
    reject(service.validateDelegation(issued.getDelegationToken()));
    assertThat(introspections).hasValue(1);
    assertThat(parentChecks).hasValue(3);
  }

  @Test
  void serviceIdentityIsDistinctAndNotDerivedFromHeaderNodeId() {
    var interceptor = new OAuthServiceIdentityInterceptor(properties);
    for (String supplied : List.of("", IDENTITY, "client-controlled-node-id")) {
      @SuppressWarnings("unchecked")
      ServerCall<Object, Object> call = mock(ServerCall.class);
      when(call.getMethodDescriptor())
          .thenReturn((io.grpc.MethodDescriptor) OAuthIdentityServiceGrpc.getExchangeTokenMethod());
      var headers = new Metadata();
      if (!supplied.isEmpty()) headers.put(OAuthServiceCredentials.KEY, supplied);
      @SuppressWarnings("unchecked")
      ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
      interceptor.interceptCall(call, headers, next);
      verifyNoInteractions(next);
      verify(call).close(argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED), any());
    }
    @SuppressWarnings("unchecked")
    ServerCall<Object, Object> call = mock(ServerCall.class);
    when(call.getMethodDescriptor())
        .thenReturn((io.grpc.MethodDescriptor) OAuthIdentityServiceGrpc.getExchangeTokenMethod());
    var headers = new Metadata();
    headers.put(OAuthServiceCredentials.KEY, EXCHANGE);
    @SuppressWarnings("unchecked")
    ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(call, headers, next);
    verify(next).startCall(call, headers);
  }

  private String signed(String aud, long ttl, List<String> scopes, String secret) {
    Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .type("taska-delegation+jwt")
        .and()
        .issuer("urn:taska:auth:delegation")
        .subject(SUBJECT)
        .audience()
        .add(aud)
        .and()
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttl)))
        .claim("parent_grant", GRANT)
        .claim("parent_token", TOKEN)
        .claim("channel", "MCP")
        .claim("scopes", scopes)
        .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
        .compact();
  }

  private void reject(Mono<?> operation) {
    StepVerifier.create(operation)
        .expectErrorMatches(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == DomainStatus.UNAUTHENTICATED)
        .verify();
  }
}
