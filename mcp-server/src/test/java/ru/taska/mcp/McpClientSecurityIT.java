package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpClientSecurityIT {
  static final String WRITE = McpAuthenticationFilterTest.TOKEN;
  static final String READ = "tsk_pat_" + "r".repeat(43);
  static final String EXPIRED = "tsk_pat_" + "e".repeat(43);
  static final String REVOKED = "tsk_pat_" + "v".repeat(43);
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final AtomicInteger STATUS = new AtomicInteger(200);
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final Server AUTH = auth();
  static final DisposableServer GATEWAY =
      HttpServer.create()
          .port(0)
          .handle(
              (request, response) -> {
                REQUESTS.add(request.method().name() + " " + request.uri());
                return response
                    .status(STATUS.get())
                    .header("Content-Type", "application/json")
                    .header("Retry-After", "3")
                    .sendString(
                        Mono.just(
                            STATUS.get() == 200
                                ? "{\"id\":\""
                                    + WriteGatewayFixture.USER
                                    + "\",\"displayName\":\"Alice"
                                    + " E2E\",\"avatar\":null,\"avatarUnavailable\":false}"
                                : "{\"message\":\"private sql Bearer secret should not escape\"}"));
              })
          .bindNow();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("taska.mcp.auth.host", () -> "localhost");
    properties.add("taska.mcp.auth.port", AUTH::getPort);
    properties.add("taska.mcp.gateway-url", () -> "http://localhost:" + GATEWAY.port());
    properties.add(
        "taska.mcp.gateway-signing-secret",
        () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
  }

  @BeforeEach
  void reset() {
    STATUS.set(200);
    REQUESTS.clear();
  }

  @AfterAll
  static void stop() {
    AUTH.shutdownNow();
    GATEWAY.disposeNow();
  }

  @Test
  void sdkListsEntireFrozenCatalogForBothScopesAndCallsWithSameCorrelation() {
    for (String token : List.of(READ, WRITE))
      try (var client = client(token)) {
        assertThat(client.initialize().protocolVersion()).isEqualTo("2025-11-25");
        assertThat((JsonNode) JSON.valueToTree(client.listTools().tools()))
            .isEqualTo(McpCatalogSnapshotTest.toolContract(JSON));
        assertThat(
                (JsonNode)
                    JSON.valueToTree(
                        client.listPrompts().prompts().stream()
                            .sorted(java.util.Comparator.comparing(McpSchema.Prompt::name))
                            .toList()))
            .isEqualTo(
                JSON.valueToTree(
                    new PromptCatalog()
                        .definitions().stream()
                            .sorted(java.util.Comparator.comparing(McpSchema.Prompt::name))
                            .toList()));
        assertThat(
                (JsonNode)
                    JSON.valueToTree(
                        client.listResourceTemplates().resourceTemplates().stream()
                            .sorted(
                                java.util.Comparator.comparing(
                                    McpSchema.ResourceTemplate::uriTemplate))
                            .toList()))
            .isEqualTo(
                JSON.valueToTree(
                    new ResourceCatalog()
                        .taskaResourceTemplates(null).stream()
                            .map(spec -> spec.resourceTemplate())
                            .sorted(
                                java.util.Comparator.comparing(
                                    McpSchema.ResourceTemplate::uriTemplate))
                            .toList()));
        var result = client.callTool(new McpSchema.CallToolRequest("whoami", Map.of()));
        assertThat(result.isError()).isFalse();
        assertThat(JSON.readTree(((McpSchema.TextContent) result.content().get(1)).text()))
            .isEqualTo(content(result));
        assertThat(content(result).path("request_id").asText()).isEqualTo("security-sdk");
        assertThat(content(result).path("data").path("token_kind").asText()).isEqualTo("PAT");
        assertThat(
                JSON.readTree(((McpSchema.TextContent) result.content().get(1)).text())
                    .path("data")
                    .path("user")
                    .path("displayName")
                    .asText())
            .isEqualTo("Alice E2E");
      }
    assertThat(REQUESTS).containsExactly("GET /api/v1/users/me", "GET /api/v1/users/me");
  }

  @Test
  void upstreamErrorsHaveSafeStableCodesAndObjectAclIsIndistinguishable() {
    Map<Integer, String> codes =
        Map.of(
            400,
            "invalid_argument",
            401,
            "unauthenticated",
            403,
            "not_found",
            404,
            "not_found",
            409,
            "conflict",
            429,
            "rate_limited",
            500,
            "upstream_error");
    try (var client = client(WRITE)) {
      client.initialize();
      for (var error : codes.entrySet()) {
        STATUS.set(error.getKey());
        var result = client.callTool(new McpSchema.CallToolRequest("whoami", Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(content(result).path("error").path("code").asText()).isEqualTo(error.getValue());
        assertThat(JSON.writeValueAsString(result))
            .doesNotContain("private sql", "Bearer secret", WRITE);
      }
    }
  }

  @Test
  void authScopeOriginProtocolAndBodyLimitFailuresNeverReachGateway() throws Exception {
    for (String token : List.of("", EXPIRED, REVOKED, "eyJ.jwt.value"))
      assertThat(post(token, null, "{}").statusCode()).isEqualTo(401);
    assertThat(post(WRITE, "https://evil.invalid", "{}").statusCode()).isEqualTo(403);
    var denied =
        post(
            READ,
            null,
            JSON.writeValueAsString(
                Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    1,
                    "method",
                    "tools/call",
                    "params",
                    Map.of(
                        "name",
                        "create_issue",
                        "arguments",
                        Map.of(
                            "project_key",
                            "TAS",
                            "summary",
                            "Denied",
                            "idempotency_key",
                            WriteToolsTest.KEY)))));
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(denied.headers().firstValue("WWW-Authenticate").orElse(""))
        .contains("insufficient_scope");
    assertThat(post(WRITE, null, " ".repeat(65537)).statusCode()).isEqualTo(413);
    try (var client = client(WRITE)) {
      client.initialize();
      assertThatThrownBy(
              () -> client.callTool(new McpSchema.CallToolRequest("unknown_tool", Map.of())))
          .isInstanceOf(McpError.class);
      assertThatThrownBy(
              () ->
                  client.callTool(
                      new McpSchema.CallToolRequest("get_issue", Map.of("issue_key", ""))))
          .isInstanceOf(McpError.class);
    }
    assertThat(REQUESTS).isEmpty();
  }

  private HttpResponse<String> post(String token, String origin, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream");
    if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
    if (origin != null) request.header("Origin", origin);
    return HttpClient.newHttpClient()
        .send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private McpSyncClient client(String token) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(
                    HttpRequest.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "security-sdk"))
                .build())
        .requestTimeout(Duration.ofSeconds(10))
        .build();
  }

  private static JsonNode content(McpSchema.CallToolResult result) {
    return JSON.valueToTree(result.structuredContent());
  }

  private static Server auth() {
    try {
      return ServerBuilder.forPort(0)
          .addService(
              new ReactorAuthServiceGrpc.AuthServiceImplBase() {
                @Override
                public Mono<ValidateAccessTokenResponse> validateAccessToken(
                    Mono<ValidateAccessTokenRequest> request) {
                  return request.flatMap(
                      value -> {
                        String token = value.getBody().getAccessToken();
                        if (!List.of(WRITE, READ, EXPIRED).contains(token))
                          return Mono.error(Status.UNAUTHENTICATED.asRuntimeException());
                        var user =
                            McpAuthenticationFilterTest.user().toBuilder()
                                .setUserId(WriteGatewayFixture.USER);
                        if (WRITE.equals(token)) user.addScopes("write");
                        if (EXPIRED.equals(token))
                          user.setTokenExpiresAt(Timestamp.newBuilder().setSeconds(1));
                        return Mono.just(
                            ValidateAccessTokenResponse.newBuilder().setUserContext(user).build());
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
