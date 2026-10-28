package ru.taska.security.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.*;
import ru.taska.api.oauth.v1.*;
import ru.taska.exception.*;
import ru.taska.service.AuthService;

class ConnectedAppsServiceTest {
  static final String OWNER = UUID.randomUUID().toString(), APP = UUID.randomUUID().toString();
  HttpServer server;
  final AtomicInteger calls = new AtomicInteger();
  AuthService auth;
  ConnectedAppsGrpcService service;

  @BeforeEach
  void prepare() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/users/" + OWNER + "/apps",
        exchange -> {
          calls.incrementAndGet();
          String expected =
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(
                          ("auth-service:" + "I".repeat(32)).getBytes(StandardCharsets.UTF_8));
          assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(expected);
          if (exchange.getRequestMethod().equals("DELETE")) {
            assertThat(exchange.getRequestURI().getPath()).endsWith("/" + APP);
            exchange.sendResponseHeaders(204, -1);
          } else {
            byte[] data =
                ("[{\"appId\":\""
                        + APP
                        + "\",\"name\":\"Claude\",\"host\":\"claude.ai\",\"metadataUrl\":null,\"scopes\":[\"future\",\"read\"],\"connectedAt\":\"2026-01-01T00:00:00Z\",\"lastUsedAt\":\"2026-01-02T00:00:00Z\",\"expiresAt\":\"2026-12-01T00:00:00Z\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
          }
          exchange.close();
        });
    server.start();
    auth = mock(AuthService.class);
    when(auth.validateAccessToken("session"))
        .thenReturn(
            Mono.just(
                UserContext.newBuilder()
                    .setUserId(OWNER)
                    .setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_JWT)
                    .build()));
    service = new ConnectedAppsGrpcService(auth, new OAuthIntrospectionClient(properties(true)));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void brokerPreservesUnknownScopesNullMetadataAndUsesOwnSessionForRevoke() {
    var response = service.listConnectedApps(Mono.just(list(OWNER))).block();
    assertThat(response.getAvailable()).isTrue();
    assertThat(response.getItems(0).getScopesList()).containsExactly("future", "read");
    assertThat(response.getItems(0).hasMetadataUrl()).isFalse();
    service
        .revokeConnectedApp(
            Mono.just(
                RevokeConnectedAppRequest.newBuilder()
                    .setHeader(header())
                    .setUserId(OWNER)
                    .setSessionAccessToken("session")
                    .setAppId(APP)
                    .build()))
        .block();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void disabledOAuthReturnsUnavailableFlagWithoutNetwork() {
    service = new ConnectedAppsGrpcService(auth, new OAuthIntrospectionClient(properties(false)));
    assertThat(service.listConnectedApps(Mono.just(list(OWNER))).block().getAvailable()).isFalse();
    assertThat(calls.get()).isZero();
  }

  @Test
  void foreignOwnerAndNonSessionTokensNeverReachServer() {
    StepVerifier.create(service.listConnectedApps(Mono.just(list(UUID.randomUUID().toString()))))
        .expectErrorMatches(
            e -> io.grpc.Status.fromThrowable(e).getCode() == io.grpc.Status.Code.PERMISSION_DENIED)
        .verify();
    for (var kind :
        List.of(
            AccessTokenKind.ACCESS_TOKEN_KIND_PAT, AccessTokenKind.ACCESS_TOKEN_KIND_DELEGATION)) {
      when(auth.validateAccessToken("session"))
          .thenReturn(
              Mono.just(UserContext.newBuilder().setUserId(OWNER).setTokenKind(kind).build()));
      StepVerifier.create(service.listConnectedApps(Mono.just(list(OWNER))))
          .expectErrorMatches(
              e ->
                  io.grpc.Status.fromThrowable(e).getCode() == io.grpc.Status.Code.PERMISSION_DENIED
                      && "SESSION_REQUIRED"
                          .equals(
                              mapper.GrpcErrorDetails.decode(
                                      io.grpc.Status.trailersFromThrowable(e))
                                  .get("reason")))
          .verify();
    }
    assertThat(calls.get()).isZero();
  }

  @Test
  void serverOutageMapsToUnavailable() {
    server.stop(0);
    StepVerifier.create(service.listConnectedApps(Mono.just(list(OWNER))))
        .expectErrorMatches(
            e -> io.grpc.Status.fromThrowable(e).getCode() == io.grpc.Status.Code.UNAVAILABLE)
        .verify();
  }

  private OAuthBridgeProperties properties(boolean enabled) {
    var endpoint =
        URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/grants/validate");
    return new OAuthBridgeProperties(
        enabled,
        URI.create("https://oauth.example.test"),
        URI.create("https://mcp.example.test/mcp"),
        endpoint,
        endpoint,
        "auth-service",
        "I".repeat(32),
        "J".repeat(32),
        "K".repeat(32),
        "L".repeat(32));
  }

  private Header header() {
    return Header.newBuilder().setNodeId("gateway").setRequestId("request").build();
  }

  private ListConnectedAppsRequest list(String owner) {
    return ListConnectedAppsRequest.newBuilder()
        .setHeader(header())
        .setUserId(owner)
        .setSessionAccessToken("session")
        .build();
  }
}
