package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest {
  static final AtomicBoolean REVOKED = new AtomicBoolean();
  static final AtomicInteger AUTH_CALLS = new AtomicInteger();
  static final AtomicReference<String> FORWARDED_TOKEN = new AtomicReference<>();
  static final AtomicReference<String> FORWARDED_ID = new AtomicReference<>();
  static final AtomicBoolean GATEWAY_HEALTHY = new AtomicBoolean(true);
  static final AtomicInteger GATEWAY_STATUS = new AtomicInteger(200);
  static final Server AUTH = startAuth();
  static final DisposableServer GATEWAY =
      HttpServer.create()
          .port(0)
          .handle(
              (request, response) -> {
                if ("/actuator/health".equals(request.uri())) {
                  return response
                      .status(GATEWAY_HEALTHY.get() ? 200 : 503)
                      .header(HttpHeaders.CONTENT_TYPE, "application/json")
                      .sendString(Mono.just("{}"));
                }
                assertThat(request.uri()).isEqualTo("/api/v1/users/me");
                FORWARDED_TOKEN.set(request.requestHeaders().get(HttpHeaders.AUTHORIZATION));
                FORWARDED_ID.set(request.requestHeaders().get("X-Request-Id"));
                var headers = request.requestHeaders();
                assertThat(headers.get(ru.taska.security.McpGatewaySignature.VERSION_HEADER)).isEqualTo("1");
                String timestamp = headers.get(ru.taska.security.McpGatewaySignature.TIMESTAMP_HEADER);
                String nonce = headers.get(ru.taska.security.McpGatewaySignature.NONCE_HEADER);
                assertThat(Long.parseLong(timestamp)).isBetween(java.time.Instant.now().getEpochSecond() - 60,
                    java.time.Instant.now().getEpochSecond() + 5);
                assertThat(nonce).matches("[A-Za-z0-9_-]{22}");
                byte[] expected = ru.taska.security.McpGatewaySignature.sign(new byte[32], request.method().name(),
                    java.net.URI.create(request.uri()), FORWARDED_ID.get(), timestamp, nonce,
                    FORWARDED_TOKEN.get().substring("Bearer ".length()));
                assertThat(headers.get(ru.taska.security.McpGatewaySignature.SIGNATURE_HEADER))
                    .isEqualTo(ru.taska.security.McpGatewaySignature.encode(expected));
                return response
                    .status(GATEWAY_STATUS.get())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .sendString(
                        Mono.just(
                            "{\"id\":\"user-1\",\"email\":\"user@example.test\","
                                + "\"displayName\":\"Имя\",\"status\":\"ACTIVE\",\"globalRole\":\"USER\","
                                + "\"avatar\":null,\"avatarUnavailable\":false}"));
              })
          .bindNow();

  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Value("${spring.ai.mcp.server.version}")
  String buildVersion;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("taska.mcp.gateway-signing-secret", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    properties.add("taska.mcp.auth.port", AUTH::getPort);
    properties.add("taska.mcp.auth.host", () -> "localhost");
    properties.add("taska.mcp.gateway-url", () -> "http://localhost:" + GATEWAY.port());
  }

  @AfterAll
  static void stop() {
    AUTH.shutdownNow();
    GATEWAY.disposeNow();
  }

  @Test
  void realSdkClientInitializesListsCallsWhoamiAndSeesRevocation() {
    REVOKED.set(false);
    GATEWAY_STATUS.set(200);
    var transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .requestBuilder(
                HttpRequest.newBuilder()
                    .header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
                    .header("X-Request-Id", "sdk-test"))
            .build();
    try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
      var initialized = client.initialize();
      assertThat(initialized.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(initialized.serverInfo().name()).isEqualTo("taska");
      assertThat(initialized.serverInfo().version()).isEqualTo(buildVersion);
      assertThat(buildVersion).doesNotContain("@");
      assertThat(client.listTools().tools())
          .extracting(McpSchema.Tool::name)
          .containsExactly(
              "add_comment",
              "add_label",
              "assign_issue",
              "create_issue",
              "get_board",
              "get_issue",
              "get_project",
              "link_issues",
              "list_comments",
              "list_notifications",
              "list_projects",
              "list_worklogs",
              "log_work",
              "mark_notifications_read",
              "remove_label",
              "search_issues",
              "search_users",
              "transition_issue",
              "unwatch_issue",
              "update_issue",
              "watch_issue",
              "whoami");
      var result = client.callTool(new McpSchema.CallToolRequest("whoami", Map.of()));
      assertThat(result.isError()).isFalse();
      Map<?, ?> content = (Map<?, ?>) result.structuredContent();
      Map<?, ?> data = (Map<?, ?>) content.get("data");
      assertThat(data.get("token_kind")).isEqualTo("PAT");
      assertThat(result.content()).hasSize(1);
      assertThat(FORWARDED_TOKEN.get()).isEqualTo("Bearer " + McpAuthenticationFilterTest.TOKEN);
      assertThat(FORWARDED_ID.get()).isEqualTo("sdk-test");
      assertThat(AUTH_CALLS.get()).isGreaterThanOrEqualTo(4);
      GATEWAY_STATUS.set(503);
      assertThat(client.callTool(new McpSchema.CallToolRequest("whoami", Map.of())).isError())
          .isTrue();
      REVOKED.set(true);
      http()
          .post()
          .uri("/mcp")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
          .bodyValue("{}")
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*");
    } finally {
      REVOKED.set(false);
      GATEWAY_STATUS.set(200);
    }
  }

  @Test
  void transportHasNoLegacySseOrServerSessionAndRequiresAuthAndOrigin() {
    http().post().uri("/mcp").bodyValue("{}").exchange().expectStatus().isUnauthorized();
    http()
        .post()
        .uri(java.net.URI.create("http://localhost:" + port + "/mcp;parameter=value"))
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    http()
        .post()
        .uri(java.net.URI.create("http://localhost:" + port + "/m%63p"))
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    http()
        .post()
        .uri("/mcp")
        .header(HttpHeaders.ORIGIN, "https://evil.test")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden();
    http()
        .get()
        .uri("/mcp")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
        .exchange()
        .expectStatus()
        .isEqualTo(405);
    http().get().uri("/sse").exchange().expectStatus().isNotFound();
    http()
        .post()
        .uri("/mcp")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
        .header("MCP-Protocol-Version", "2099-01-01")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isBadRequest();
    http().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    http().get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    GATEWAY_HEALTHY.set(false);
    try {
      http().get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);
      http().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    } finally {
      GATEWAY_HEALTHY.set(true);
    }
  }

  private WebTestClient http() {
    return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static Server startAuth() {
    try {
      return ServerBuilder.forPort(0)
          .addService(
              new ReactorAuthServiceGrpc.AuthServiceImplBase() {
                @Override
                public Mono<ValidateAccessTokenResponse> validateAccessToken(
                    Mono<ValidateAccessTokenRequest> request) {
                  return request.flatMap(
                      value -> {
                        AUTH_CALLS.incrementAndGet();
                        assertThat(value.getBody().getAllowPersonalToken()).isTrue();
                        assertThat(value.getHeader().getNodeId()).isEqualTo("mcp-server");
                        if (REVOKED.get()) {
                          return Mono.error(Status.UNAUTHENTICATED.asRuntimeException());
                        }
                        return Mono.just(
                            ValidateAccessTokenResponse.newBuilder()
                                .setUserContext(McpAuthenticationFilterTest.user())
                                .build());
                      });
                }
              })
          .build()
          .start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }
}
