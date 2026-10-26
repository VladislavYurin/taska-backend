package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourcesPromptsSdkIntegrationTest {
  static final ResourceServiceTest GATEWAY = fixture();
  static final Server AUTH = startAuth();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("taska.mcp.auth.host", () -> "localhost");
    properties.add("taska.mcp.auth.port", AUTH::getPort);
    properties.add("taska.mcp.gateway-url", () -> "http://localhost:" + GATEWAY.server.port());
    properties.add(
        "taska.mcp.gateway-signing-secret",
        () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
  }

  @AfterAll
  static void stop() {
    AUTH.shutdownNow();
    GATEWAY.stop();
  }

  @Test
  void realSdkDiscoversTemplatesReadsMarkdownAndRendersRussianPromptsWithoutCrossUserListCache() {
    try (var first = client(ResourceServiceTest.TOKEN_A);
        var second = client(ResourceServiceTest.TOKEN_B)) {
      var init = first.initialize();
      second.initialize();
      assertThat(init.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(init.capabilities().resources()).isNotNull();
      assertThat(Boolean.TRUE.equals(init.capabilities().resources().subscribe())).isFalse();
      assertThat(Boolean.TRUE.equals(init.capabilities().resources().listChanged())).isFalse();
      assertThat(init.capabilities().prompts()).isNotNull();
      assertThat(Boolean.TRUE.equals(init.capabilities().prompts().listChanged())).isFalse();
      assertThat(first.listResourceTemplates().resourceTemplates())
          .extracting(value -> value.uriTemplate())
          .containsExactlyInAnyOrder("taska://issues/{key}", "taska://projects/{key}");
      assertThat(first.listResources().resources())
          .extracting(value -> value.uri())
          .containsExactly("taska://issues/TAS-1");
      assertThat(second.listResources().resources())
          .extracting(value -> value.uri())
          .containsExactly("taska://issues/OTHER-1");
      assertThat(first.listResources().resources())
          .extracting(value -> value.uri())
          .containsExactly("taska://issues/TAS-1");
      var issue = first.readResource(new McpSchema.ReadResourceRequest("taska://issues/TAS-1"));
      assertThat(((McpSchema.TextResourceContents) issue.contents().getFirst()).text())
          .contains("Задача TAS", "недоверенные данные", "Description");
      assertThat(issue.meta())
          .containsEntry("request_id", "sdk-resources")
          .containsEntry("truncated", false);
      var project = first.readResource(new McpSchema.ReadResourceRequest("taska://projects/TAS"));
      assertThat(((McpSchema.TextResourceContents) project.contents().getFirst()).text())
          .contains("Workflows", "Участники", "Типы задач");
      assertThat(first.listPrompts().prompts())
          .extracting(value -> value.name())
          .containsExactlyInAnyOrder("triage_my_issues", "standup", "decompose_issue");
      for (String name : List.of("triage_my_issues", "standup", "decompose_issue")) {
        int calls = GATEWAY.requests.size();
        var result =
            first.getPrompt(
                new McpSchema.GetPromptRequest(
                    name,
                    name.equals("decompose_issue") ? Map.of("issue_key", "TAS-1") : Map.of()));
        assertThat(((McpSchema.TextContent) result.messages().getFirst().content()).text())
            .contains("недоверенные", "get_issue");
        assertThat(GATEWAY.requests).hasSize(calls);
      }
      assertThatThrownBy(
              () -> first.getPrompt(new McpSchema.GetPromptRequest("decompose_issue", Map.of())))
          .isInstanceOf(McpError.class)
          .satisfies(
              error ->
                  assertThat(((McpError) error).getJsonRpcError().code())
                      .isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
      var foreign = notFound(first, "taska://issues/FOREIGN-1");
      var missing = notFound(first, "taska://issues/MISSING-1");
      assertThat(foreign.getJsonRpcError()).isEqualTo(missing.getJsonRpcError());
    }
  }

  @Test
  void resourceResponseUses2025PrivateNoStoreHttpContract() {
    WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .post()
        .uri("/mcp")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ResourceServiceTest.TOKEN_A)
        .header("MCP-Protocol-Version", "2025-11-25")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\",\"params\":{}}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .expectBody(String.class)
        .value(
            value ->
                assertThat(value)
                    .contains("taska://issues/TAS-1")
                    .doesNotContain("ttlMs", "cacheScope", "token_id", "tokenName"));
  }

  McpSyncClient client(String token) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(
                    HttpRequest.newBuilder()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Request-Id", "sdk-resources"))
                .build())
        .requestTimeout(Duration.ofSeconds(8))
        .build();
  }

  private static McpError notFound(McpSyncClient client, String uri) {
    try {
      client.readResource(new McpSchema.ReadResourceRequest(uri));
      throw new AssertionError("expected missing resource");
    } catch (McpError error) {
      assertThat(error.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND);
      return error;
    }
  }

  static ResourceServiceTest fixture() {
    var value = new ResourceServiceTest();
    value.setup();
    value.responses.put(
        "user-b",
        value.mapper.valueToTree(
            Map.of("items", List.of(value.shortIssue("OTHER-1", "Private B")), "totalCount", 1)));
    value.statuses.put("/api/v1/issues/by-key/FOREIGN-1", 403);
    value.statuses.put("/api/v1/issues/by-key/MISSING-1", 404);
    return value;
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
                        var user =
                            McpAuthenticationFilterTest.user().toBuilder()
                                .setUserId(ResourceServiceTest.USER);
                        if (ResourceServiceTest.TOKEN_B.equals(value.getBody().getAccessToken()))
                          user.setUserId("22222222-2222-4222-8222-222222222222")
                              .setTokenId("33333333-3333-4333-8333-333333333333");
                        return ValidateAccessTokenResponse.newBuilder()
                            .setUserContext(user)
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
