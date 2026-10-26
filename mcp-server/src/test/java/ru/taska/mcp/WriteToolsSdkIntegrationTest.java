package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WriteToolsSdkIntegrationTest {
  static final WriteGatewayFixture GATEWAY = new WriteGatewayFixture();
  static final Server AUTH = startAuth();
  final JsonMapper mapper = JsonMapper.builder().build();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("taska.mcp.auth.host", () -> "localhost");
    properties.add(
        "taska.mcp.gateway-signing-secret",
        () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    properties.add("taska.mcp.auth.port", AUTH::getPort);
    properties.add("taska.mcp.gateway-url", () -> "http://localhost:" + GATEWAY.server.port());
  }

  @AfterAll
  static void stop() {
    AUTH.shutdownNow();
    GATEWAY.close();
  }

  @Test
  void realSdkWritesWithStableIdempotencyAndReportsConcurrentChanges() {
    var transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .requestBuilder(
                HttpRequest.newBuilder()
                    .header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
                    .header("X-Request-Id", "sdk-write-test"))
            .build();
    try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(8)).build()) {
      assertThat(client.initialize().protocolVersion()).isEqualTo("2025-11-25");
      assertThat(client.listTools().tools())
          .hasSize(22)
          .anySatisfy(
              tool -> {
                assertThat(tool.name()).isEqualTo("add_comment");
                assertThat(tool.annotations().readOnlyHint()).isFalse();
                assertThat(tool.annotations().idempotentHint()).isFalse();
                assertThat(tool.annotations().destructiveHint()).isFalse();
              });
      var create =
          Map.<String, Object>of(
              "project_key",
              "TAS",
              "summary",
              "SDK issue",
              "idempotency_key",
              WriteToolsTest.KEY,
              "assignee",
              "me",
              "labels",
              java.util.List.of("bug"));
      var first = client.callTool(new McpSchema.CallToolRequest("create_issue", create));
      var repeat = client.callTool(new McpSchema.CallToolRequest("create_issue", create));
      assertThat(first.isError()).as(first.structuredContent().toString()).isFalse();
      assertThat(repeat.isError()).isFalse();
      assertThat(GATEWAY.creates).hasValue(1);
      assertThat(content(first).path("data").path("issueKey").asText()).isEqualTo("TAS-1");
      assertThat(content(first).path("request_id").asText()).isEqualTo("sdk-write-test");
      var transition =
          client.callTool(
              new McpSchema.CallToolRequest(
                  "transition_issue", Map.of("issue_key", "TAS-1", "target_status", "Готово")));
      assertThat(transition.isError()).as(transition.structuredContent().toString()).isFalse();
      assertThat(content(transition).path("data").path("issue").path("status").asText())
          .isEqualTo("READY");
      GATEWAY.conflictPatch.set(true);
      var conflict =
          client.callTool(
              new McpSchema.CallToolRequest(
                  "update_issue", Map.of("issue_key", "TAS-1", "summary", "Concurrent edit")));
      assertThat(conflict.isError()).isTrue();
      assertThat(content(conflict).path("error").path("details").path("current_version").asInt())
          .isEqualTo(7);
      assertThat(
              client
                  .callTool(
                      new McpSchema.CallToolRequest(
                          "add_comment", Map.of("issue_key", "TAS-1", "body", "SDK comment")))
                  .isError())
          .isFalse();
      assertThat(
              client
                  .callTool(
                      new McpSchema.CallToolRequest(
                          "log_work",
                          Map.of(
                              "issue_key",
                              "TAS-1",
                              "spent_minutes",
                              30,
                              "work_date",
                              "2026-10-20")))
                  .isError())
          .isFalse();
      assertThat(GATEWAY.comments).hasValue(1);
      assertThat(GATEWAY.worklogs).hasValue(1);
      assertThat(GATEWAY.calls)
          .allSatisfy(
              call -> {
                assertThat(call.authorization())
                    .isEqualTo("Bearer " + McpAuthenticationFilterTest.TOKEN);
                assertThat(call.requestId()).isEqualTo("sdk-write-test");
              });
    }
  }

  private JsonNode content(McpSchema.CallToolResult result) {
    return mapper.valueToTree(result.structuredContent());
  }

  private static Server startAuth() {
    try {
      return ServerBuilder.forPort(0)
          .addService(
              new ReactorAuthServiceGrpc.AuthServiceImplBase() {
                @Override
                public Mono<ValidateAccessTokenResponse> validateAccessToken(
                    Mono<ValidateAccessTokenRequest> request) {
                  return request.map(
                      value -> {
                        assertThat(value.getBody().getAllowPersonalToken()).isTrue();
                        return ValidateAccessTokenResponse.newBuilder()
                            .setUserContext(
                                McpAuthenticationFilterTest.user().toBuilder()
                                    .setUserId(WriteGatewayFixture.USER)
                                    .addScopes("write"))
                            .build();
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
